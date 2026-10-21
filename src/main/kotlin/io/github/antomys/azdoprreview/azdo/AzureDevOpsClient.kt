package io.github.antomys.azdoprreview.azdo

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * A failed call. [status] is the HTTP status, or 0 when the service couldn't be reached. The message is meant for the
 * user: it never holds the PAT, the Authorization header or a response body, only the status and the service's `message`.
 */
class AzureDevOpsException(val status: Int, message: String, val kind: Kind = Kind.of(status)) : Exception(message) {
    /** How the UI should react, following the shared error table in `docs/features/F1-F5.md`. */
    enum class Kind {
        /** The token is wrong, expired or revoked: sign in again. */
        REJECTED,

        /** The token lacks a scope or permission. */
        FORBIDDEN,

        /** Not found, or no access: Azure DevOps doesn't tell them apart. */
        NOT_FOUND,
        THROTTLED,
        SERVICE,
        NETWORK,
        OTHER;

        companion object {
            fun of(status: Int) = when (status) {
                0 -> NETWORK
                401 -> REJECTED
                403 -> FORBIDDEN
                404 -> NOT_FOUND
                429 -> THROTTLED
                in 500..599 -> SERVICE
                else -> OTHER
            }
        }
    }
}

/**
 * Minimal Azure DevOps REST transport: PAT auth, JSON errors, and the service's throttling headers.
 *
 * The PAT only ever goes into the Authorization header; it is never part of a message or log line.
 */
class AzureDevOpsClient(
    /** `https://dev.azure.com/{org}` */
    val orgUrl: String,
    /** `https://vssps.dev.azure.com/{org}` (identity and Graph APIs) */
    val identityUrl: String,
    pat: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxRetries: Int = 3,
    /** Called before a "token rejected" error is thrown, so the owner can drop this client and ask to sign in. */
    private val onRejected: () -> Unit = {},
) {
    private val authorization = "Basic " + Base64.getEncoder().encodeToString(":$pat".toByteArray())

    /** Epoch millis before which no request may be sent, set from `Retry-After`. */
    private val nextRequestAt = AtomicLong(0)

    /** Returns the response body of a 2xx response; throws [AzureDevOpsException] otherwise. */
    suspend fun send(method: String, url: String, jsonBody: String? = null, accept: String = "application/json"): ByteArray {
        var attempt = 0
        while (true) {
            val wait = nextRequestAt.get() - clock()
            if (wait > 0) sleep(wait)

            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization)
                .header("Accept", accept)
                .method(method, jsonBody?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            if (jsonBody != null) builder.header("Content-Type", "application/json")
            val response = try {
                http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
            } catch (e: IOException) {
                // Connect or read timeout, DNS failure, refused connection. The cause isn't shown: the host is enough.
                throw AzureDevOpsException(0, "Can't reach ${URI(url).host}.")
            }

            // Retry-After may arrive on successful responses too: it asks the client to slow down.
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
            if (retryAfter != null) nextRequestAt.set(clock() + retryAfter * 1000)

            val status = response.statusCode()
            when {
                // A rejected PAT can produce a 203 with the HTML sign-in page instead of a 401.
                status == 203 -> throw rejected("HTTP 203 sign-in page")
                status in 200..299 -> return response.body()
                // A wrong PAT gets a 302 to the sign-in page (verified); redirects aren't followed.
                status in 300..399 && isSignIn(response) -> throw rejected("HTTP $status sign-in redirect")
                status == 429 && attempt < maxRetries -> {
                    attempt++
                    if (retryAfter == null) nextRequestAt.set(clock() + 1000L * attempt)
                }
                status == 401 -> throw rejected(detail(status, response.body()))
                else -> throw AzureDevOpsException(status, errorMessage(status, response.body()))
            }
        }
    }

    private fun rejected(detail: String): AzureDevOpsException {
        onRejected()
        return AzureDevOpsException(401, "$REJECTED_MESSAGE ($detail)")
    }

    private fun isSignIn(response: HttpResponse<*>) =
        response.headers().firstValue("Location").orElse("").contains("_signin", ignoreCase = true)

    private fun errorMessage(status: Int, body: ByteArray): String {
        val detail = detail(status, body)
        return when (AzureDevOpsException.Kind.of(status)) {
            AzureDevOpsException.Kind.FORBIDDEN -> "The token can't do this. It needs: $SCOPES. ($detail)"
            AzureDevOpsException.Kind.NOT_FOUND ->
                "Project, repository or pull request not found, or the token has no access to it. ($detail)"
            AzureDevOpsException.Kind.THROTTLED -> "Azure DevOps is limiting requests. Try again in a minute. ($detail)"
            AzureDevOpsException.Kind.SERVICE -> "Azure DevOps is unavailable ($detail)."
            else -> detail
        }
    }

    /** "HTTP 404" or "HTTP 404: <the service's message>"; nothing else from the body. */
    private fun detail(status: Int, body: ByteArray): String {
        val message = runCatching {
            Json.parseToJsonElement(body.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        return if (message.isNullOrBlank()) "HTTP $status" else "HTTP $status: $message"
    }

    companion object {
        const val REJECTED_MESSAGE = "Azure DevOps rejected the token. It may have expired or been revoked."
        const val SCOPES = "Code (Read & write), Identity (Read), Graph (Read)"

        fun forOrganization(organization: String, pat: String, onRejected: () -> Unit = {}) = AzureDevOpsClient(
            "https://dev.azure.com/$organization", "https://vssps.dev.azure.com/$organization", pat, onRejected = onRejected,
        )

        /** Joins [base] and [path] and appends the non-null [query] values, URL-encoded. */
        fun url(base: String, path: String, vararg query: Pair<String, Any?>): String {
            val params = query.filter { it.second != null }
                .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v.toString())}" }
            return base.trimEnd('/') + path + if (params.isEmpty()) "" else "?$params"
        }

        /** Encodes one URL path segment or query value. */
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
