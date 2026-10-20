package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel.State
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PullRequestListViewModelTest {
    private val repo = RepoRef("project", "repo")
    private val review = ReviewSummary(
        ReviewRef(repo, 7), "Title", ReviewUser("u", "User"), "feature", "main", ReviewState.OPEN, false, Instant.EPOCH,
    )

    @Test
    fun `loads open pull requests by default`() = runBlocking {
        val provider = FakeReviewProvider(reviews = listOf(review))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }

        vm.refresh().join()

        assertEquals(State.Loaded(listOf(review)), vm.state.value)
        assertEquals(listOf(ReviewFilter(ReviewState.OPEN)), provider.filters)
        assertEquals("https://example.test/pr/7", vm.webUrl(review))
    }

    @Test
    fun `quick filters use the current user's id`() = runBlocking {
        val provider = FakeReviewProvider(me = ReviewUser("me-id", "Me"))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }

        vm.quickFilter = QuickFilter.CREATED_BY_ME
        vm.refresh().join()
        vm.quickFilter = QuickFilter.ASSIGNED_TO_ME
        vm.reviewState = null
        vm.refresh().join()

        assertEquals(
            listOf(ReviewFilter(ReviewState.OPEN, authorId = "me-id"), ReviewFilter(null, reviewerId = "me-id")),
            provider.filters,
        )
    }

    @Test
    fun `reports missing settings`() = runBlocking {
        val vm = PullRequestListViewModel(this) { null }

        vm.refresh().join()

        assertEquals(State.NotConfigured, vm.state.value)
        assertNull(vm.webUrl(review))
    }

    @Test
    fun `reports service errors`() = runBlocking {
        val provider = FakeReviewProvider(failure = AzureDevOpsException(401, "HTTP 401: Access denied"))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }

        vm.refresh().join()

        assertEquals(State.Failed("HTTP 401: Access denied"), vm.state.value)
    }
}
