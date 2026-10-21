package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.FixtureServer.Companion.fixture
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewRef
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The provider's response handling must stay off the calling (EDT) dispatcher and must not repeat Graph lookups. */
class AzureDevOpsProviderThreadingTest {
    private val server = FixtureServer()
    private val clockThreads = CopyOnWriteArrayList<String>()
    private val provider = AzureDevOpsProvider(
        AzureDevOpsClient(
            server.baseUrl + "/fabrikam", server.baseUrl + "/vssps", "test-pat",
            clock = { clockThreads += Thread.currentThread().name; System.currentTimeMillis() },
            sleep = {},
        ),
    )
    private val repo = RepoRef("test-pr-reviewer", "test-pr-reviewer")
    private val ref = ReviewRef(repo, 1)
    private val prPath = "/fabrikam/test-pr-reviewer/_apis/git/repositories/test-pr-reviewer/pullRequests/1"

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `handles responses off the calling dispatcher`() {
        // Retry-After makes the client read the clock after the response arrives, which shows where the response is handled.
        server.on("GET", prPath, fixture("pull-request.json"), headers = mapOf("Retry-After" to "0"))
        val edt = Executors.newSingleThreadExecutor { Thread(it, "edt-like") }
        try {
            runBlocking(edt.asCoroutineDispatcher()) { provider.getReview(ref) }
        } finally {
            edt.shutdown()
        }

        assertTrue(clockThreads.size >= 2, "clock calls: $clockThreads")
        // Coroutine debug mode appends " @coroutine#n" to thread names, hence the prefix match.
        val afterResponse = clockThreads.drop(1)
        assertTrue(afterResponse.none { it.startsWith("edt-like") }, "response handled on the calling dispatcher: $clockThreads")
    }

    @Test
    fun `looks up a graph descriptor's storage key only once`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/projects/test-pr-reviewer/teams", """{"message":"denied"}""", status = 403)
        server.on("POST", "/vssps/_apis/graph/subjectquery", fixture("subject-query.json"))
        server.on("GET", "/vssps/_apis/graph/storagekeys/aad.dGVzdC11c2Vy", fixture("storage-key.json"))

        val first = provider.searchUsers(repo, "Tes", 10)
        val second = provider.searchUsers(repo, "Tes", 10)

        assertEquals(first, second)
        assertEquals("11111111-1111-1111-1111-111111111111", first.single().id)
        assertEquals(2, server.requests("POST", "/vssps/_apis/graph/subjectquery").size)
        assertEquals(1, server.requests("GET", "/vssps/_apis/graph/storagekeys/aad.dGVzdC11c2Vy").size, "storage key lookups")
    }
}
