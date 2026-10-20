package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.DiffSide
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class PullRequestDetailsViewModelTest {
    private val repo = RepoRef("project", "repo")
    private val summary = ReviewSummary(
        ReviewRef(repo, 1), "Title", ReviewUser("u", "User"), "feature", "main", ReviewState.OPEN, false, Instant.EPOCH,
    )
    private val details = ReviewDetails(summary, "Description", emptyList())
    private val first = Revision(1, "src1", "target", "base")
    private val latest = Revision(2, "src2", "target", "base")
    private val edited = FileChange("/a.cs", "/a.cs", ChangeKind.MODIFIED)

    @Test
    fun `loads details with the latest revision's changes`() = runBlocking {
        val provider = FakeReviewProvider(
            details = details,
            revisions = listOf(first, latest),
            changes = mapOf(1 to emptyList(), 2 to listOf(edited)),
        )
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)

        vm.refresh().join()

        assertEquals(State.Loaded(details, latest, listOf(edited)), vm.state.value)
    }

    @Test
    fun `fails clearly when the pull request has no iterations`() = runBlocking {
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(FakeReviewProvider(details = details), repo), summary)

        vm.refresh().join()

        assertEquals(State.Failed("The pull request has no iterations yet"), vm.state.value)
    }

    @Test
    fun `reports service errors`() = runBlocking {
        val provider = FakeReviewProvider(failure = AzureDevOpsException(404, "HTTP 404: not found"))
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)

        vm.refresh().join()

        assertEquals(State.Failed("HTTP 404: not found"), vm.state.value)
    }

    @Test
    fun `compares the merge base with the revision's source commit`() {
        assertEquals(
            DiffSide("/a.cs", "base") to DiffSide("/a.cs", "src2"),
            PullRequestDetailsViewModel.diffSides(edited, latest),
        )
        assertEquals(
            null to DiffSide("/new.cs", "src2"),
            PullRequestDetailsViewModel.diffSides(FileChange(null, "/new.cs", ChangeKind.ADDED), latest),
        )
        assertEquals(
            DiffSide("/old.cs", "base") to null,
            PullRequestDetailsViewModel.diffSides(FileChange("/old.cs", null, ChangeKind.DELETED), latest),
        )
        assertEquals(
            DiffSide("/old.md", "base") to DiffSide("/new.md", "src2"),
            PullRequestDetailsViewModel.diffSides(FileChange("/old.md", "/new.md", ChangeKind.RENAMED), latest),
        )
    }
}
