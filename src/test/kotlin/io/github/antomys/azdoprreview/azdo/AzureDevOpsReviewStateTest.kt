package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.FixtureServer.Companion.fixture
import io.github.antomys.azdoprreview.azdo.FixtureServer.Reply
import io.github.antomys.azdoprreview.review.AssignmentRole
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewMark
import io.github.antomys.azdoprreview.review.ReviewMarkStore
import io.github.antomys.azdoprreview.review.ReviewMarks
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewStatus
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.RevisionReason
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.ListFilters
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * F2–F5 against responses recorded from the sandbox (scrubbed): team assignments, local review marks and what changed
 * since the last view. Marks are local, so every request here must be a GET.
 */
class AzureDevOpsReviewStateTest {
    private val server = FixtureServer()
    private val provider = AzureDevOpsProvider(
        AzureDevOpsClient(server.baseUrl + "/fabrikam", server.baseUrl + "/vssps", "test-pat", sleep = {}),
    )
    private val repo = RepoRef("test-pr-reviewer", "test-pr-reviewer")
    private val target = ReviewTarget(provider, repo)
    private val ref = ReviewRef(repo, 1)
    private val repoPath = "/fabrikam/test-pr-reviewer/_apis/git/repositories/test-pr-reviewer"
    private val prPath = "$repoPath/pullRequests/1"
    private val markKey = "fabrikam/$REPOSITORY_ID/1/$USER_ID"
    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @AfterEach
    fun stop() = server.close()

    /** Marks are local: nothing may be written to Azure DevOps. */
    private fun assertOnlyReads() = assertTrue(server.requests.all { it.method == "GET" }, "only reads: ${server.requests.map { it.method }}")

    @Test
    fun `lists my teams in the project`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/projects/test-pr-reviewer/teams", fixture("teams-mine.json"))
        val team = provider.myTeams(repo).single()

        assertEquals(TEAM_ID, team.id)
        assertEquals("test-pr-reviewer Team", team.displayName)
        assertTrue("%24mine=true" in server.requests.single().query.orEmpty())
    }

    @Test
    fun `maps the list's reviewer, repository and source commit fields`() = runBlocking {
        val reviewers = """[{"id":"$USER_ID","vote":-5,"isRequired":true,"hasDeclined":true,"votedFor":[{"id":"$TEAM_ID"}]}]"""
        server.on("GET", "$repoPath/pullrequests", list(pr(1, reviewers)))
        val summary = provider.listReviews(repo, ReviewFilter()).single()

        assertEquals(REPOSITORY_ID, summary.repositoryId)
        assertEquals(COMMIT_2, summary.sourceCommit)
        val reviewer = summary.reviewers.single()
        assertTrue(reviewer.hasDeclined)
        assertEquals(listOf(TEAM_ID), reviewer.votedFor)
    }

    @Test
    fun `maps iteration dates and reasons`() = runBlocking {
        server.on("GET", "$prPath/iterations", fixture("iterations.json"))
        val revisions = provider.listRevisions(ref)

        assertEquals(listOf(setOf(RevisionReason.PUSH), setOf(RevisionReason.PUSH)), revisions.map { it.reasons })
        assertEquals(Instant.parse("2026-10-20T14:09:15.6540256Z"), revisions[1].created)
    }

    // F2

    @Test
    fun `assigned to me merges my and my team's pull requests, with roles and votes`() = runBlocking {
        fun me(vote: Int, required: Boolean, declined: Boolean = false) =
            """[{"id":"$USER_ID","vote":$vote,"isRequired":$required,"hasDeclined":$declined}]"""
        val team = """[{"id":"$TEAM_ID","displayName":"[test-pr-reviewer]\\test-pr-reviewer Team","vote":-10,"isContainer":true}]"""
        val both = """[{"id":"$TEAM_ID","vote":0},{"id":"$USER_ID","vote":5}]"""
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "/fabrikam/_apis/projects/test-pr-reviewer/teams", fixture("teams-mine.json"))
        server.on(
            "GET", "$repoPath/pullrequests",
            Reply(200, list(pr(11, me(10, true)), pr(12, me(5, false)), pr(13, me(0, true)), pr(14, me(-5, false)), pr(15, me(-10, true)), pr(3, both), pr(16, me(0, false, declined = true))).toByteArray()),
            Reply(200, list(pr(4, team), pr(3, both)).toByteArray()),
        )
        val vm = PullRequestListViewModel(this, ReviewMarkStore.InMemory()) { target }
        vm.setFilters(ListFilters(assignedToMe = true))
        vm.refresh().join()

        val queries = server.requests("GET", "$repoPath/pullrequests").map { it.query.orEmpty() }
        assertTrue("searchCriteria.reviewerId=$USER_ID" in queries[0] && "searchCriteria.status=active" in queries[0], queries[0])
        assertTrue("searchCriteria.reviewerId=$TEAM_ID" in queries[1], queries[1])
        val assignments = vm.assignments.value
        assertEquals(listOf(11, 12, 13, 14, 15, 3, 16, 4), (vm.state.value as PullRequestListViewModel.State.Loaded).reviews.map { it.ref.id })
        assertEquals(
            listOf(Vote.APPROVED, Vote.APPROVED_WITH_SUGGESTIONS, Vote.NONE, Vote.WAITING_FOR_AUTHOR, Vote.REJECTED),
            (11..15).map { assignments.getValue(it).vote },
        )
        assertEquals(listOf("Required", "Optional", "Required", "Optional", "Required"), (11..15).map { assignments.getValue(it).label })
        assertEquals("via team test-pr-reviewer Team", assignments.getValue(4).label)
        assertEquals(Vote.REJECTED, assignments.getValue(4).vote)
        // On it both directly and through the team: listed once, with the direct role.
        assertEquals(AssignmentRole.OPTIONAL, assignments.getValue(3).role)
        // Declined is hidden until "Show declined".
        assertTrue(16 !in visible(vm))
        vm.setFilters(vm.filters.value.copy(showDeclined = true))
        assertTrue(16 in visible(vm))
        assertNull(vm.notice.value)
    }

    @Test
    fun `a team query that fails doesn't fail the list`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "/fabrikam/_apis/projects/test-pr-reviewer/teams", fixture("teams-mine.json"))
        server.on(
            "GET", "$repoPath/pullrequests",
            Reply(200, list(pr(2, """[{"id":"$USER_ID","vote":0,"isRequired":true}]""")).toByteArray()),
            Reply(403, """{"message":"denied"}""".toByteArray()),
        )
        val vm = PullRequestListViewModel(this, ReviewMarkStore.InMemory()) { target }
        vm.setFilters(ListFilters(assignedToMe = true))
        vm.refresh().join()

        assertEquals(listOf(2), visible(vm))
        assertEquals("Some team assignments couldn't be loaded", vm.notice.value)
    }

    // F3 and F4

    @Test
    fun `marking reads the iterations and writes nothing to Azure DevOps`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "$repoPath/pullrequests", fixture("pull-requests.json"))
        server.on("GET", "$prPath/iterations", fixture("iterations.json"))
        val store = ReviewMarkStore.InMemory()
        val vm = PullRequestListViewModel(this, store) { target }
        vm.refresh().join()
        val pr = (vm.state.value as PullRequestListViewModel.State.Loaded).reviews.single()

        assertEquals(ReviewMarks.MarkResult.Marked, vm.markReviewed(pr))

        assertEquals(ReviewMark(reviewedIteration = 2, reviewedSourceCommit = COMMIT_2), store.get(markKey)?.copy(touched = Instant.EPOCH))
        assertEquals(emptyList<Int>(), visible(vm))
        vm.setFilters(vm.filters.value.copy(showReviewed = true))
        assertEquals(listOf(1), visible(vm))
        vm.markNotReviewed(pr)
        vm.setFilters(vm.filters.value.copy(showReviewed = false))
        assertEquals(listOf(1), visible(vm))
        assertOnlyReads()
    }

    @Test
    fun `an unchanged source commit costs no iterations call`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "$repoPath/pullrequests", fixture("pull-requests.json"))
        val store = ReviewMarkStore.InMemory()
        store.set(markKey, ReviewMark(reviewedIteration = 2, reviewedSourceCommit = COMMIT_2))
        val vm = PullRequestListViewModel(this, store) { target }
        vm.refresh().join()

        assertEquals(0, server.requests("GET", "$prPath/iterations").size)
        assertEquals(ReviewStatus.REVIEWED, store.get(markKey)?.status)
        assertEquals(emptyList<Int>(), visible(vm))
    }

    @Test
    fun `a new source commit in the list is confirmed with one iterations call and clears the mark`() = runBlocking {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "$repoPath/pullrequests", fixture("pull-requests.json"))
        server.on("GET", "$prPath/iterations", fixture("iterations.json"))
        val store = ReviewMarkStore.InMemory()
        store.set(markKey, ReviewMark(reviewedIteration = 1, reviewedSourceCommit = COMMIT_1, lastViewedIteration = 1, lastViewedSourceCommit = COMMIT_1))
        val vm = PullRequestListViewModel(this, store) { target }
        vm.refresh().join()

        assertEquals(1, server.requests("GET", "$prPath/iterations").size)
        val mark = store.get(markKey)!!
        assertEquals(ReviewStatus.UPDATED, mark.status)
        assertEquals(1 to 2, mark.updatedFrom to mark.updatedTo)
        assertEquals(setOf(RevisionReason.PUSH), mark.updateReasons)
        assertEquals(1, mark.lastViewedIteration)
        // Updated pull requests are back in the default list.
        assertEquals(listOf(1), visible(vm))
        assertOnlyReads()
    }

    // F5

    @Test
    fun `opening shows what changed since the last view, then records this view`() = runBlocking {
        detailsFixtures()
        val store = ReviewMarkStore.InMemory()
        store.set(markKey, ReviewMark(lastViewedIteration = 1, lastViewedSourceCommit = COMMIT_1))
        val summary = provider.listReviews(repo, ReviewFilter()).single()
        val vm = PullRequestDetailsViewModel(this, target, summary, marksStore = store) { now }
        vm.refresh().join()

        val update = vm.update.value!!
        assertEquals("Updated: iteration 1 → 2", update.title)
        assertEquals(COMMIT_1, update.fromCommit)
        assertEquals(listOf(2), update.iterations.map { it.id })
        assertEquals(listOf(COMMIT_2), update.commits?.map { it.id })
        assertEquals("Guard against division by zero", update.commits?.single()?.message)
        assertEquals(listOf("/src/Calculator.cs"), update.files?.map { it.newPath })
        assertEquals("1 new commit", update.commitsText)
        assertEquals("1 file changed", update.filesText)
        val changes = server.requests("GET", "$prPath/iterations/2/changes").map { it.query.orEmpty() }
        assertTrue(changes.any { "%24compareTo=1" in it }, changes.toString())
        assertTrue(server.requests("GET", "$prPath/iterations/2/commits").all { "top=500" in it.query.orEmpty() && "skip=0" in it.query.orEmpty() })
        assertEquals(2, store.get(markKey)?.lastViewedIteration)
        assertEquals(COMMIT_2, store.get(markKey)?.lastViewedSourceCommit)

        // The next view has nothing new to show.
        val again = PullRequestDetailsViewModel(this, target, summary, marksStore = store) { now }
        again.refresh().join()
        assertNull(again.update.value)
        assertOnlyReads()
    }

    @Test
    fun `pages through iteration commits 500 at a time`() = runBlocking {
        val commit = """{"commitId":"%s","comment":"c"}"""
        val full = (1..500).joinToString(",") { commit.format("a$it") }
        server.on(
            "GET", "$prPath/iterations/2/commits",
            Reply(200, """{"value":[$full],"count":500}""".toByteArray()),
            Reply(200, """{"value":[${commit.format("last")}],"count":1}""".toByteArray()),
        )
        assertEquals(501, provider.listCommits(ref, 2).size)
        assertTrue("skip=500" in server.requests.last().query.orEmpty())
    }

    @Test
    fun `a comparison doesn't replace the file ids used to anchor threads`() = runBlocking {
        server.on(
            "GET", "$prPath/iterations/2/changes",
            Reply(200, fixture("iteration-changes-compare.json").toByteArray()),
            Reply(200, fixture("iteration-changes.json").toByteArray()),
        )
        val thread = Json.parseToJsonElement(fixture("threads.json")).jsonObject.getValue("value").jsonArray.first()
        server.on("POST", "$prPath/threads", thread.toString())
        val revision = Revision(2, COMMIT_2, "t", "b")
        assertEquals(listOf("/src/Calculator.cs"), provider.listChanges(ref, revision, compareTo = 1).map { it.newPath })

        // Greeter.cs isn't in the comparison, so anchoring a thread to it loads the full change list.
        provider.createThread(ref, revision, ThreadAnchor("/src/Greeter.cs", LineRange(Side.RIGHT, 1, 1)), "x")
        assertEquals(2, server.requests("GET", "$prPath/iterations/2/changes").size)
        val body = Json.parseToJsonElement(server.requests("POST", "$prPath/threads").single().body).jsonObject
        assertEquals("3", body.getValue("pullRequestThreadContext").jsonObject.getValue("changeTrackingId").toString())
    }

    private fun detailsFixtures() {
        server.on("GET", "/fabrikam/_apis/connectionData", fixture("connection-data.json"))
        server.on("GET", "$repoPath/pullrequests", fixture("pull-requests.json"))
        server.on("GET", prPath, fixture("pull-request.json"))
        server.on("GET", "$prPath/iterations", fixture("iterations.json"))
        // The merge-base changes load first, then the comparison with the last viewed iteration.
        server.on(
            "GET", "$prPath/iterations/2/changes",
            Reply(200, fixture("iteration-changes.json").toByteArray()),
            Reply(200, fixture("iteration-changes-compare.json").toByteArray()),
        )
        server.on("GET", "$prPath/iterations/1/commits", fixture("iteration-commits-1.json"))
        server.on("GET", "$prPath/iterations/2/commits", fixture("iteration-commits-2.json"))
        server.on("GET", "$prPath/threads", fixture("threads.json"))
        server.on("GET", "/vssps/_apis/identities", fixture("identities.json"))
    }

    private fun visible(vm: PullRequestListViewModel) = (vm.state.value as PullRequestListViewModel.State.Loaded).visible.map { it.ref.id }

    /** pull-request.json as pull request [id] with [reviewers]. */
    private fun pr(id: Int, reviewers: String = "[]"): JsonObject {
        val pr = Json.parseToJsonElement(fixture("pull-request.json")).jsonObject
        return JsonObject(pr + mapOf("pullRequestId" to Json.parseToJsonElement("$id"), "reviewers" to Json.parseToJsonElement(reviewers)))
    }

    private fun list(vararg prs: JsonObject) = """{"value":[${prs.joinToString(",")}],"count":${prs.size}}"""

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        const val TEAM_ID = "44444444-4444-4444-4444-444444444444"
        const val REPOSITORY_ID = "c3ce6006-127f-41ef-94a9-71b2d23db326"
        const val COMMIT_1 = "751a09ebfae4b3c88fb27a8f47063b6b80860f2c"
        const val COMMIT_2 = "1e7f4c2ef495547142e693aa2f56d8cee5ebf71d"
    }
}
