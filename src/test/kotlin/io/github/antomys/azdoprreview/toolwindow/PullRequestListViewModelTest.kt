package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewThread
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
        assertEquals(provider, vm.target?.provider)
    }

    @Test
    fun `author and reviewer filters go to the service, label and search filter the loaded list`() = runBlocking {
        val labelled = review.copy(ref = ReviewRef(repo, 8), title = "Pricing", labels = listOf("ui"))
        val provider = FakeReviewProvider(reviews = listOf(review, labelled))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }
        vm.refresh().join()

        vm.setFilters(ListFilters(author = ReviewUser("a", "A"), reviewer = ReviewUser("r", "R"), state = null))
        vm.refresh().join()
        assertEquals(ReviewFilter(null, authorId = "a", reviewerId = "r"), provider.filters.last())

        val requests = provider.filters.size
        vm.setFilters(vm.filters.value.copy(label = "ui"))
        assertEquals(listOf(8), (vm.state.value as State.Loaded).visible.map { it.ref.id })
        vm.setFilters(vm.filters.value.copy(label = null, search = "#7"))
        assertEquals(listOf(7), (vm.state.value as State.Loaded).visible.map { it.ref.id })
        vm.setFilters(vm.filters.value.copy(search = "pric"))
        assertEquals(listOf(8), (vm.state.value as State.Loaded).visible.map { it.ref.id })
        assertEquals(requests, provider.filters.size)
    }

    @Test
    fun `counts each pull request's discussion threads after loading`() = runBlocking {
        val comment = ReviewComment(1, null, ReviewUser("u", "User"), "x", Instant.EPOCH, false)
        val threads = listOf(
            ReviewThread(1, null, null, isSystem = true, comments = listOf(comment)),
            ReviewThread(2, null, null, isSystem = false, comments = listOf(comment)),
            ReviewThread(3, null, null, isSystem = false, comments = listOf(comment.copy(isDeleted = true))),
        )
        val provider = FakeReviewProvider(reviews = listOf(review), threads = threads)
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }

        vm.refresh().join()

        assertEquals(mapOf(7 to 1), vm.commentCounts.value)
    }

    @Test
    fun `reports missing settings`() = runBlocking {
        val vm = PullRequestListViewModel(this) { null }

        vm.refresh().join()

        assertEquals(State.NotConfigured, vm.state.value)
        assertNull(vm.target)
    }

    @Test
    fun `reports service errors`() = runBlocking {
        val provider = FakeReviewProvider(failure = AzureDevOpsException(401, "HTTP 401: Access denied"))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }

        vm.refresh().join()

        assertEquals(State.Failed("HTTP 401: Access denied"), vm.state.value)
    }
}
