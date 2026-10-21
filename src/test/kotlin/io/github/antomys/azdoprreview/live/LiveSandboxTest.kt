package io.github.antomys.azdoprreview.live

import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider
import io.github.antomys.azdoprreview.azdo.AzureDevOpsSignIn
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel
import io.github.antomys.azdoprreview.toolwindow.ListFilters
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Read-only checks against the sandbox PRs listed in docs/sandbox-inventory.md, through the same view models the
 * tool window uses. Opt-in: runs only with AZDO_LIVE_TEST=1 and AZDO_PAT set; never part of normal or CI runs.
 */
@EnabledIfEnvironmentVariable(named = "AZDO_LIVE_TEST", matches = "1")
class LiveSandboxTest {
    private val repo = RepoRef("test-pr-reviewer", "test-pr-reviewer")
    private val provider = AzureDevOpsProvider(
        AzureDevOpsClient.forOrganization("igorvolokhovych", requireNotNull(System.getenv("AZDO_PAT")) { "AZDO_PAT not set" }),
    )
    private val target = ReviewTarget(provider, repo)

    /** [filters] with the current user filled in as author or reviewer. */
    private fun list(filters: (me: ReviewUser) -> ListFilters): List<Int> = runBlocking {
        val vm = PullRequestListViewModel(this) { target }
        vm.setFilters(filters(provider.currentUser()))
        vm.refresh().join()
        val state = vm.state.value
        check(state is PullRequestListViewModel.State.Loaded) { "list failed: $state" }
        state.reviews.map { it.ref.id }.sorted()
    }

    private fun details(id: Int) = runBlocking {
        val summary = provider.listReviews(repo, ReviewFilter(state = null)).first { it.ref.id == id }
        val vm = PullRequestDetailsViewModel(this, target, summary)
        vm.refresh().join()
        val state = vm.state.value
        check(state is PullRequestDetailsViewModel.State.Loaded) { "details failed: $state" }
        state
    }

    @Test
    fun `list filters match the sandbox inventory`() {
        assertEquals((1..9).toList(), list { ListFilters() })
        assertEquals((1..9).toList(), list { ListFilters(author = it) })
        // reviewerId doesn't expand teams: the team-only PR 4 is not "assigned to me" yet (F2 adds the team union).
        assertEquals(listOf(2, 3, 5, 7, 8), list { ListFilters(reviewer = it) })
    }

    @Test
    fun `details show reviewers and votes`() {
        val optional = details(3).details.reviewers.single()
        assertEquals(Vote.APPROVED_WITH_SUGGESTIONS, optional.vote)
        assertFalse(optional.isRequired)
        assertTrue(details(2).details.reviewers.single().isRequired)
        assertTrue(details(6).details.reviewers.isEmpty())
    }

    @Test
    fun `changes and diff sides for the sample PR`() = runBlocking {
        val loaded = details(1)
        assertEquals(2, loaded.revision.id)
        val byKind = loaded.changes.associateBy { it.kind }
        assertEquals(setOf(ChangeKind.MODIFIED, ChangeKind.ADDED, ChangeKind.DELETED, ChangeKind.RENAMED), byKind.keys)

        // What the diff viewer loads for each kind: both sides for an edit, one side for add/delete.
        val (left, right) = PullRequestDetailsViewModel.diffSides(byKind.getValue(ChangeKind.MODIFIED), loaded.revision)
        val before = provider.fileContent(repo, left!!.path, left.commit)!!.decodeToString()
        val after = provider.fileContent(repo, right!!.path, right.commit)!!.decodeToString()
        assertFalse("Multiply" in before)
        assertTrue("Multiply" in after && "DivideByZeroException" in after)

        assertNull(PullRequestDetailsViewModel.diffSides(byKind.getValue(ChangeKind.ADDED), loaded.revision).first)
        assertNull(PullRequestDetailsViewModel.diffSides(byKind.getValue(ChangeKind.DELETED), loaded.revision).second)
        val renamed = PullRequestDetailsViewModel.diffSides(byKind.getValue(ChangeKind.RENAMED), loaded.revision)
        assertNotNull(provider.fileContent(repo, renamed.first!!.path, renamed.first!!.commit))
        assertNotNull(provider.fileContent(repo, renamed.second!!.path, renamed.second!!.commit))
    }

    @Test
    fun `empty PR has no changes`() {
        assertTrue(details(6).changes.isEmpty())
    }

    @Test
    fun `threads of the sample PR`() = runBlocking {
        // Threads 1-6 were seeded; UI testing adds more (docs/sandbox-inventory.md), so only those are checked.
        val threads = provider.listThreads(ReviewRef(repo, 1)).associateBy { it.id }
        assertTrue(threads.getValue(1).isSystem)
        assertTrue((2..6).none { threads.getValue(it).isSystem })
    }

    /** A client of its own, as the sign-in panel uses. */
    private fun signInClient() = AzureDevOpsClient.forOrganization("igorvolokhovych", requireNotNull(System.getenv("AZDO_PAT")))

    @Test
    fun `sign-in validates the token and the repository`() = runBlocking {
        val user = AzureDevOpsSignIn.validate(signInClient(), repo)
        assertEquals(provider.currentUser().id, user.id)
    }

    @Test
    fun `sign-in maps a wrong token to rejected`() {
        var rejected = 0
        val client = AzureDevOpsClient.forOrganization("igorvolokhovych", "not-a-valid-token") { rejected++ }
        val e = assertThrows<AzureDevOpsException> { runBlocking { AzureDevOpsSignIn.validate(client, repo) } }
        assertEquals(AzureDevOpsException.Kind.REJECTED, e.kind)
        assertEquals(1, rejected)
    }

    @Test
    fun `sign-in maps an unknown repository to not found`() {
        val e = assertThrows<AzureDevOpsException> {
            runBlocking { AzureDevOpsSignIn.validate(signInClient(), RepoRef("test-pr-reviewer", "no-such-repo")) }
        }
        assertEquals(AzureDevOpsException.Kind.NOT_FOUND, e.kind)
    }

    @Test
    fun `assigned to me includes the team-only PR once, with roles and votes`() = runBlocking {
        val vm = PullRequestListViewModel(this) { target }
        vm.setFilters(ListFilters(assignedToMe = true))
        vm.refresh().join()
        val state = vm.state.value
        check(state is PullRequestListViewModel.State.Loaded) { "list failed: $state" }

        assertEquals(listOf(2, 3, 4, 5, 7, 8), state.reviews.map { it.ref.id }.sorted())
        val assignments = vm.assignments.value
        assertEquals("Required", assignments.getValue(2).label)
        assertEquals("Optional", assignments.getValue(3).label)
        assertEquals(Vote.APPROVED_WITH_SUGGESTIONS, assignments.getValue(3).vote)
        assertTrue(assignments.getValue(4).label.startsWith("via team "), assignments.getValue(4).label)
        assertNull(vm.notice.value)
    }

    @Test
    fun `iteration commits and the changes since iteration 1 of the sample PR`() = runBlocking {
        val ref = ReviewRef(repo, 1)
        val revisions = provider.listRevisions(ref)
        val seen = provider.listCommits(ref, 1).map { it.id }.toSet()
        assertEquals(1, provider.listCommits(ref, 2).count { it.id !in seen })
        assertEquals(listOf("/src/Calculator.cs"), provider.listChanges(ref, revisions.maxBy { it.id }, compareTo = 1).map { it.newPath })
    }
}
