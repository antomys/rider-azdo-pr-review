package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.review.RepoRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AzureDevOpsSignInTest {
    private val server = FixtureServer()
    private val client = AzureDevOpsClient(server.baseUrl + "/fabrikam", server.baseUrl + "/vssps", "test-pat-value")
    private val repo = RepoRef("Fiber Project", "web-app")
    private val repoPath = "/fabrikam/Fiber%20Project/_apis/git/repositories/web-app"

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `returns the token's user and checks the repository`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", FixtureServer.fixture("connection-data.json"))
        server.on("GET", repoPath, """{"id":"00000000-0000-0000-0000-000000000000","name":"web-app"}""")

        val user = AzureDevOpsSignIn.validate(client, repo)

        assertEquals("11111111-1111-1111-1111-111111111111", user.id)
        assertEquals("Test User", user.displayName)
        assertEquals(1, server.requests("GET", repoPath).size)
    }

    @Test
    fun `reports a repository the token can't see`() {
        server.on("GET", "/fabrikam/_apis/connectionData", FixtureServer.fixture("connection-data.json"))
        server.on("GET", repoPath, """{"message":"TF401019: The Git repository does not exist"}""", status = 404)

        val e = assertThrows<AzureDevOpsException> { runBlocking { AzureDevOpsSignIn.validate(client, repo) } }

        assertEquals(AzureDevOpsException.Kind.NOT_FOUND, e.kind)
    }

    @Test
    fun `skips the repository check without one`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", FixtureServer.fixture("connection-data.json"))

        AzureDevOpsSignIn.validate(client, null)

        assertEquals(1, server.requests.size)
    }
}
