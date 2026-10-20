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
    )
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
        assertEquals("HTTP 401: Access denied", e.message)
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
    fun `encodes query values and skips nulls`() {
        val url = AzureDevOpsClient.url("https://x/", "/a b", "q" to "c d&e", "skip" to null, "api-version" to "7.1")
        assertEquals("https://x/a b?q=c%20d%26e&api-version=7.1", url)
        assertTrue(AzureDevOpsClient.encode("a b/c") == "a%20b%2Fc")
    }

    private companion object {
        const val PAT = "test-pat-value"
        const val NOW = 1_000_000L
    }
}
