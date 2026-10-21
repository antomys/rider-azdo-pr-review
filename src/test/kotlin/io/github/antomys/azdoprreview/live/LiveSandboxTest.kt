package io.github.antomys.azdoprreview.live

import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient
import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel
import io.github.antomys.azdoprreview.toolwindow.QuickFilter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun list(filter: QuickFilter): List<Int> = runBlocking {
        val vm = PullRequestListViewModel(this) { target }
        vm.quickFilter = filter
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
        assertEquals(listOf(1, 2, 3, 4, 5, 6), list(QuickFilter.ALL))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), list(QuickFilter.CREATED_BY_ME))
        // reviewerId doesn't expand teams: the team-only PR 4 is not "assigned to me" yet (F2 adds the team union).
        assertEquals(listOf(2, 3, 5), list(QuickFilter.ASSIGNED_TO_ME))
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
        val threads = provider.listThreads(ReviewRef(repo, 1))
        assertEquals(6, threads.size)
        assertEquals(1, threads.count { it.isSystem })
    }
}
