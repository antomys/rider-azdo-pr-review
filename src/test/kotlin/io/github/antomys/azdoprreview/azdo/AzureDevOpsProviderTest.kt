package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.FixtureServer.Companion.fixture
import io.github.antomys.azdoprreview.azdo.FixtureServer.Reply
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Replays responses recorded from sandbox PR 1 (scrubbed); write calls are checked by request body. */
class AzureDevOpsProviderTest {
    private val server = FixtureServer()
    private val provider = AzureDevOpsProvider(
        AzureDevOpsClient(server.baseUrl + "/fabrikam", server.baseUrl + "/vssps", "test-pat", sleep = {}),
    )
    private val repo = RepoRef("test-pr-reviewer", "test-pr-reviewer")
    private val ref = ReviewRef(repo, 1)
    private val repoPath = "/fabrikam/test-pr-reviewer/_apis/git/repositories/test-pr-reviewer"
    private val prPath = "$repoPath/pullRequests/1"
    private val revision2 = Revision(2, "src", "target", "base")

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `lists open pull requests`() = runBlocking {
        server.on("GET", "$repoPath/pullrequests", fixture("pull-requests.json"))
        val pr = provider.listReviews(repo, ReviewFilter()).single()

        assertEquals(ref, pr.ref)
        assertEquals("Sample change for review testing", pr.title)
        assertEquals("Test User", pr.author.displayName)
        assertEquals("feature/review-sample", pr.sourceBranch)
        assertEquals("main", pr.targetBranch)
        assertEquals(ReviewState.OPEN, pr.state)
        val query = server.requests.single().query.orEmpty()
        assertTrue("searchCriteria.status=active" in query, query)
        assertTrue("api-version=7.1" in query, query)
    }

    @Test
    fun `maps iterations to revisions`() = runBlocking {
        server.on("GET", "$prPath/iterations", fixture("iterations.json"))
        val revisions = provider.listRevisions(ref)

        assertEquals(listOf(1, 2), revisions.map { it.id })
        assertTrue(revisions.all { it.sourceCommit.length == 40 && it.baseCommit.length == 40 })
    }

    @Test
    fun `maps change kinds and takes a deleted file's path from originalPath`() = runBlocking {
        server.on("GET", "$prPath/iterations/2/changes", fixture("iteration-changes.json"))
        val changes = provider.listChanges(ref, revision2)

        assertEquals(
            setOf(
                FileChange("/docs/notes.md", "/docs/NOTES.md", ChangeKind.RENAMED),
                FileChange("/src/Calculator.cs", "/src/Calculator.cs", ChangeKind.MODIFIED),
                FileChange(null, "/src/Greeter.cs", ChangeKind.ADDED),
                FileChange("/src/Legacy.cs", null, ChangeKind.DELETED),
            ),
            changes.toSet(),
        )
        assertTrue("%24top=2000" in server.requests.single().query.orEmpty())
    }

    @Test
    fun `follows change pages until nextSkip and nextTop are zero`() = runBlocking {
        server.on(
            "GET", "$prPath/iterations/2/changes",
            Reply(200, """{"changeEntries":[{"changeTrackingId":1,"changeType":"add","item":{"path":"/a"}}],"nextSkip":1,"nextTop":2000}""".toByteArray()),
            Reply(200, """{"changeEntries":[{"changeTrackingId":2,"changeType":"add","item":{"path":"/b"}}]}""".toByteArray()),
        )
        assertEquals(listOf("/a", "/b"), provider.listChanges(ref, revision2).map { it.newPath })
        assertTrue("%24skip=1" in server.requests.last().query.orEmpty())
    }

    @Test
    fun `maps every thread position type, statuses, replies and system threads`() = runBlocking {
        server.on("GET", "$prPath/threads", fixture("threads.json"))
        val threads = provider.listThreads(ref).associateBy { it.id }

        assertEquals(6, threads.size)
        threads.getValue(1).let { assertTrue(it.isSystem); assertNull(it.status) }
        threads.getValue(2).let {
            assertEquals(ThreadAnchor("/src/Calculator.cs", LineRange(Side.RIGHT, 9, 9)), it.anchor)
            assertEquals("active", it.status?.id)
            assertEquals(listOf(null, 1), it.comments.map { c -> c.parentId })
            assertEquals(setOf(USER_ID), AzureDevOpsMentions.ids(it.comments.first().body))
        }
        threads.getValue(3).let {
            assertEquals(ThreadAnchor("/src/Calculator.cs", LineRange(Side.LEFT, 9, 9)), it.anchor)
            assertEquals("wontFix", it.status?.id)
            assertTrue(it.status!!.isResolved)
        }
        assertEquals(ThreadAnchor("/src/Calculator.cs", LineRange(Side.RIGHT, 11, 15)), threads.getValue(4).anchor)
        assertEquals("fixed", threads.getValue(4).status?.id)
        assertEquals(ThreadAnchor("/src/Greeter.cs", null), threads.getValue(5).anchor)
        assertNull(threads.getValue(6).anchor)
        assertFalse(threads.values.any { it.status?.id == "unknown" })
    }

    @Test
    fun `creates a line-range thread anchored to the iteration and change tracking id`() = runBlocking {
        server.on("GET", "$prPath/iterations/2/changes", fixture("iteration-changes.json"))
        server.on("POST", "$prPath/threads", thread(4))
        val created = provider.createThread(ref, revision2, ThreadAnchor("/src/Calculator.cs", LineRange(Side.RIGHT, 11, 15)), "Consider an expression body here.")

        assertEquals(4, created.id)
        assertJson(
            """{"comments":[{"content":"Consider an expression body here.","parentCommentId":0,"commentType":"text"}],
                "status":"active",
                "threadContext":{"filePath":"/src/Calculator.cs","rightFileStart":{"line":11,"offset":1},"rightFileEnd":{"line":15,"offset":1}},
                "pullRequestThreadContext":{"changeTrackingId":2,"iterationContext":{"firstComparingIteration":2,"secondComparingIteration":2}}}""",
            server.requests("POST", "$prPath/threads").single().body,
        )
    }

    @Test
    fun `creates file-level and review-level threads, fetching changes once`() = runBlocking {
        server.on("GET", "$prPath/iterations/2/changes", fixture("iteration-changes.json"))
        server.on("POST", "$prPath/threads", thread(5))
        provider.createThread(ref, revision2, ThreadAnchor("/src/Greeter.cs"), "File comment")
        provider.createThread(ref, revision2, ThreadAnchor("/src/Legacy.cs", LineRange(Side.LEFT, 3, 3)), "Deleted file comment")
        provider.createThread(ref, revision2, null, "Review comment")

        val bodies = server.requests("POST", "$prPath/threads").map { it.body }
        assertJson(
            """{"comments":[{"content":"File comment","parentCommentId":0,"commentType":"text"}],"status":"active",
                "threadContext":{"filePath":"/src/Greeter.cs"},
                "pullRequestThreadContext":{"changeTrackingId":3,"iterationContext":{"firstComparingIteration":2,"secondComparingIteration":2}}}""",
            bodies[0],
        )
        assertJson(
            """{"comments":[{"content":"Deleted file comment","parentCommentId":0,"commentType":"text"}],"status":"active",
                "threadContext":{"filePath":"/src/Legacy.cs","leftFileStart":{"line":3,"offset":1},"leftFileEnd":{"line":3,"offset":1}},
                "pullRequestThreadContext":{"changeTrackingId":4,"iterationContext":{"firstComparingIteration":2,"secondComparingIteration":2}}}""",
            bodies[1],
        )
        assertJson("""{"comments":[{"content":"Review comment","parentCommentId":0,"commentType":"text"}],"status":"active"}""", bodies[2])
        assertEquals(1, server.requests("GET", "$prPath/iterations/2/changes").size)
    }

    @Test
    fun `refuses to anchor a thread to a file the iteration doesn't change`() {
        server.on("GET", "$prPath/iterations/2/changes", fixture("iteration-changes.json"))
        assertThrows<IllegalArgumentException> {
            runBlocking { provider.createThread(ref, revision2, ThreadAnchor("/README.md"), "x") }
        }
    }

    @Test
    fun `replies to a comment and resolves a thread`() = runBlocking {
        val comment = threadJson(2).jsonObject.getValue("comments").jsonArray[1].toString()
        server.on("POST", "$prPath/threads/2/comments", comment)
        server.on("PATCH", "$prPath/threads/4", thread(4))

        val reply = provider.reply(ref, threadId = 2, parentCommentId = 1, body = "Reply: checked, fine.")
        val resolved = provider.setThreadStatus(ref, 4, provider.capabilities.resolvedStatus)

        assertEquals(1, reply.parentId)
        assertTrue(resolved.status!!.isResolved)
        assertJson(
            """{"content":"Reply: checked, fine.","parentCommentId":1,"commentType":"text"}""",
            server.requests("POST", "$prPath/threads/2/comments").single().body,
        )
        assertJson("""{"status":"fixed"}""", server.requests("PATCH", "$prPath/threads/4").single().body)
    }

    @Test
    fun `searches users by prefix and maps them to identity ids for mentions`() = runBlocking {
        server.on("POST", "/vssps/_apis/graph/subjectquery", fixture("subject-query.json"))
        server.on("GET", "/vssps/_apis/graph/storagekeys/aad.dGVzdC11c2Vy", fixture("storage-key.json"))
        val user = provider.searchUsers("Tes").single()

        assertEquals(USER_ID, user.id)
        assertEquals("Test User", user.displayName)
        assertEquals("test.user@example.com", user.uniqueName)
        assertEquals("@<$USER_ID>", provider.encodeMention(user))
        assertJson("""{"query":"Tes","subjectKind":["User"]}""", server.requests.first().body)
        assertTrue(server.requests.all { "api-version=7.1-preview.1" in it.query.orEmpty() })
    }

    @Test
    fun `resolves mention ids to users once`() = runBlocking {
        server.on("GET", "/vssps/_apis/identities", fixture("identities.json"))
        assertEquals("Test User", provider.resolveUsers(listOf(USER_ID)).getValue(USER_ID).displayName)
        provider.resolveUsers(listOf(USER_ID))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `reads the current user from connection data`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        assertEquals(USER_ID, provider.currentUser().id)
    }

    @Test
    fun `reads file content at a commit and returns null when missing`() = runBlocking {
        server.on(
            "GET", "$repoPath/items",
            Reply(200, "class A {}".toByteArray()),
            Reply(404, """{"message":"not found"}""".toByteArray()),
        )
        assertArrayEquals("class A {}".toByteArray(), provider.fileContent(repo, "/src/A.cs", "abc123"))
        assertNull(provider.fileContent(repo, "/src/A.cs", "abc123"))

        val request = server.requests.first()
        assertEquals("application/octet-stream", request.headers["accept"])
        assertTrue("versionDescriptor.version=abc123" in request.query.orEmpty())
        assertTrue("versionDescriptor.versionType=commit" in request.query.orEmpty())
    }

    private fun threadJson(id: Int): JsonElement =
        Json.parseToJsonElement(fixture("threads.json")).jsonObject.getValue("value").jsonArray
            .first { it.jsonObject.getValue("id").toString() == "$id" }

    private fun thread(id: Int) = threadJson(id).toString()

    private fun assertJson(expected: String, actual: String) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"
    }
}
