package io.github.antomys.azdoprreview.azdo

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class AzureDevOpsException(val status: Int, message: String) : Exception(message)

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
            val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()

            // Retry-After may arrive on successful responses too: it asks the client to slow down.
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
            if (retryAfter != null) nextRequestAt.set(clock() + retryAfter * 1000)

            val status = response.statusCode()
            when {
                // A rejected PAT can produce a 203 with the HTML sign-in page instead of a 401.
                status == 203 -> throw AzureDevOpsException(401, "Authentication failed (HTTP 203 sign-in page)")
                status in 200..299 -> return response.body()
                status == 429 && attempt < maxRetries -> {
                    attempt++
                    if (retryAfter == null) nextRequestAt.set(clock() + 1000L * attempt)
                }
                else -> throw AzureDevOpsException(status, errorMessage(status, response.body()))
            }
        }
    }

    private fun errorMessage(status: Int, body: ByteArray): String {
        val message = runCatching {
            Json.parseToJsonElement(body.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        return if (message.isNullOrBlank()) "HTTP $status" else "HTTP $status: $message"
    }

    companion object {
        fun forOrganization(organization: String, pat: String) =
            AzureDevOpsClient("https://dev.azure.com/$organization", "https://vssps.dev.azure.com/$organization", pat)

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
