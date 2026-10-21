package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.FixtureServer.Reply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class AzureDevOpsClientTest {
    private val server = FixtureServer()
    private val slept = mutableListOf<Long>()
    private val client = AzureDevOpsClient(
        orgUrl = server.baseUrl + "/fabrikam",
        identityUrl = server.baseUrl + "/vssps",
        pat = PAT,
        clock = { NOW },
        sleep = { slept += it },
        onRejected = { rejections++ },
    )
    private var rejections = 0
    private val path = "/fabrikam/_apis/projects"

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `sends the PAT as basic auth with an empty user`() = runBlocking {
        server.on("GET", path, "{}")
        client.send("GET", server.baseUrl + path)
        val expected = "Basic " + Base64.getEncoder().encodeToString(":$PAT".toByteArray())
        assertEquals(expected, server.requests.single().headers["authorization"])
    }

    @Test
    fun `waits for Retry-After and retries a 429`() = runBlocking {
        server.on(
            "GET", path,
            Reply(429, """{"message":"throttled"}""".toByteArray(), mapOf("Retry-After" to "2")),
            Reply(200, "{}".toByteArray()),
        )
        assertEquals("{}", client.send("GET", server.baseUrl + path).decodeToString())
        assertEquals(listOf(2000L), slept)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `honours Retry-After on a successful response before the next request`() = runBlocking {
        server.on("GET", path, "{}", headers = mapOf("Retry-After" to "3"))
        client.send("GET", server.baseUrl + path)
        assertEquals(emptyList<Long>(), slept)
        client.send("GET", server.baseUrl + path)
        assertEquals(listOf(3000L), slept)
    }

    @Test
    fun `gives up after the retry limit`() {
        server.on("GET", path, """{"message":"throttled"}""", status = 429)
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + path) } }
        assertEquals(429, e.status)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `reports the service message without leaking the PAT`() {
        server.on("GET", path, """{"message":"Access denied"}""", status = 401)
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + path) } }
        assertEquals(401, e.status)
        assertEquals("${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401: Access denied)", e.message)
        val encoded = Base64.getEncoder().encodeToString(":$PAT".toByteArray())
        listOf(e.toString(), client.toString()).forEach {
            assertFalse(PAT in it)
            assertFalse(encoded in it)
        }
    }

    @Test
    fun `treats the 203 sign-in page as an auth failure`() {
        server.on("GET", path, Reply(203, "<html>Sign in</html>".toByteArray()))
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + path) } }
        assertEquals(401, e.status)
    }

    @Test
    fun `maps connectionData 200 to the response body`() = runBlocking {
        server.on("GET", CONNECTION_DATA, FixtureServer.fixture("connection-data.json"))
        val body = client.send("GET", server.baseUrl + CONNECTION_DATA).decodeToString()
        assertTrue("authenticatedUser" in body)
        assertEquals(0, rejections)
    }

    @Test
    fun `maps 401 to rejected and tells the owner`() {
        server.on("GET", CONNECTION_DATA, """{"message":"TF400813: not authorized"}""", status = 401)
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.REJECTED, e.kind)
        assertEquals("${AzureDevOpsClient.REJECTED_MESSAGE} (HTTP 401: TF400813: not authorized)", e.message)
        assertEquals(1, rejections)
    }

    @Test
    fun `maps a 203 HTML sign-in page to rejected`() {
        server.on("GET", CONNECTION_DATA, Reply(203, "<html><body>Sign in $PAT</body></html>".toByteArray()))
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.REJECTED, e.kind)
        assertFalse("html" in e.message!!.lowercase())
        assertFalse(PAT in e.message!!)
        assertEquals(1, rejections)
    }

    @Test
    fun `maps a redirect to the sign-in page to rejected`() {
        val location = "https://login.example.test/_signin?realm=dev.azure.com"
        server.on("GET", CONNECTION_DATA, Reply(302, "<html>Object moved</html>".toByteArray(), mapOf("Location" to location)))
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.REJECTED, e.kind)
        assertFalse("realm" in e.message!!)
        assertEquals(1, rejections)
    }

    @Test
    fun `maps 403 to the scope message`() {
        server.on("GET", CONNECTION_DATA, """{"message":"Forbidden"}""", status = 403)
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.FORBIDDEN, e.kind)
        assertEquals("The token can't do this. It needs: ${AzureDevOpsClient.SCOPES}. (HTTP 403: Forbidden)", e.message)
        assertEquals(0, rejections)
    }

    @Test
    fun `maps 404 and 5xx to their messages without the body`() {
        server.on("GET", CONNECTION_DATA, "<html>404 - Page not found</html>", status = 404)
        val notFound = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.NOT_FOUND, notFound.kind)
        assertEquals("Project, repository or pull request not found, or the token has no access to it. (HTTP 404)", notFound.message)

        server.on("GET", CONNECTION_DATA, "{}", status = 503)
        val down = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", server.baseUrl + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.SERVICE, down.kind)
        assertEquals("Azure DevOps is unavailable (HTTP 503).", down.message)
    }

    @Test
    fun `maps a refused connection to the offline message`() {
        val closed = FixtureServer().run { baseUrl.also { close() } }
        val e = assertThrows<AzureDevOpsException> { runBlocking { client.send("GET", closed + CONNECTION_DATA) } }
        assertEquals(AzureDevOpsException.Kind.NETWORK, e.kind)
        assertEquals(0, e.status)
        assertEquals("Can't reach 127.0.0.1.", e.message)
    }

    @Test
    fun `encodes query values and skips nulls`() {
        val url = AzureDevOpsClient.url("https://x/", "/a b", "q" to "c d&e", "skip" to null, "api-version" to "7.1")
        assertEquals("https://x/a b?q=c%20d%26e&api-version=7.1", url)
        assertTrue(AzureDevOpsClient.encode("a b/c") == "a%20b%2Fc")
    }

    private companion object {
        const val PAT = "test-pat-value"
        const val NOW = 1_000_000L
        const val CONNECTION_DATA = "/fabrikam/_apis/connectionData"
    }
}
