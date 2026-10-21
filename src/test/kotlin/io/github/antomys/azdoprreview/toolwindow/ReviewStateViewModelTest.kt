package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewCommit
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewMark
import io.github.antomys.azdoprreview.review.ReviewMarkStore
import io.github.antomys.azdoprreview.review.ReviewMarks
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Reviewer
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.RevisionReason
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.Companion.iterationLine
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.Companion.sinceSides
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.DiffSide
import io.github.antomys.azdoprreview.toolwindow.PullRequestListViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** F2–F5 in the list and pull request view models: hiding reviewed pull requests, sorting, and the update banner. */
class ReviewStateViewModelTest {
    private val repo = RepoRef("project", "repo")
    private val me = ReviewUser("me", "Me")
    private val r1 = Revision(1, "c1", "t", "b", Instant.parse("2026-10-20T10:00:00Z"), setOf(RevisionReason.PUSH))
    private val r2 = Revision(2, "c2", "t", "b", Instant.parse("2026-10-20T11:00:00Z"), setOf(RevisionReason.PUSH))
    private val r3 = Revision(3, "c3", "t", "b", Instant.parse("2026-10-20T12:00:00Z"), setOf(RevisionReason.REBASE, RevisionReason.FORCE_PUSH))

    private fun review(id: Int, created: Long = 0) = ReviewSummary(
        ReviewRef(repo, id), "PR $id", ReviewUser("a", "Author"), "f", "main", ReviewState.OPEN, false, Instant.ofEpochSecond(created),
        reviewers = listOf(Reviewer(me, Vote.NONE, isRequired = true)), repositoryId = "repo-guid",
    )

    private fun visible(vm: PullRequestListViewModel) = (vm.state.value as State.Loaded).visible.map { it.ref.id }

    @Test
    fun `reviewed pull requests leave the default list until shown or unmarked`() = runBlocking {
        val provider = FakeReviewProvider(me = me, reviews = listOf(review(1), review(2)), revisions = listOf(r1))
        val vm = PullRequestListViewModel(this, ReviewMarkStore.InMemory()) { ReviewTarget(provider, repo) }
        vm.refresh().join()

        assertEquals(ReviewMarks.MarkResult.Marked, vm.markReviewed(review(1)))
        assertEquals(listOf(2), visible(vm))
        assertEquals(1, vm.hiddenReviewed())

        vm.setFilters(vm.filters.value.copy(showReviewed = true))
        assertFalse(vm.filters.value.isDefault)
        assertEquals(listOf(1, 2), visible(vm))
        assertEquals(0, vm.hiddenReviewed())

        vm.setFilters(vm.filters.value.copy(showReviewed = false))
        vm.markNotReviewed(review(1))
        assertEquals(listOf(1, 2), visible(vm))
        assertEquals(1, provider.filters.size)
    }

    @Test
    fun `a mark made elsewhere applies once the list hears of it`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val provider = FakeReviewProvider(me = me, reviews = listOf(review(1)))
        val vm = PullRequestListViewModel(this, store) { ReviewTarget(provider, repo) }
        vm.refresh().join()

        store.set("fake/repo-guid/1/me", ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1"))
        vm.marksChanged()

        assertEquals(emptyList<Int>(), visible(vm))
    }

    @Test
    fun `assigned to me sorts needs review, then updated, then reviewed, newest first`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/1/me", ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1"))
        store.set("fake/repo-guid/2/me", ReviewMark(updatedFrom = 1, updatedTo = 2))
        store.set("fake/repo-guid/5/me", ReviewMark(updatedFrom = 1, updatedTo = 2))
        val reviews = listOf(review(1, created = 50), review(2, created = 10), review(3, created = 20), review(4, created = 30), review(5, created = 40))
        val provider = FakeReviewProvider(me = me, byReviewer = mapOf("me" to reviews))
        val vm = PullRequestListViewModel(this, store) { ReviewTarget(provider, repo) }
        vm.setFilters(ListFilters(assignedToMe = true, showReviewed = true))
        vm.refresh().join()

        assertEquals(listOf(4, 3, 5, 2, 1), visible(vm))
        assertEquals("Required", vm.assignments.value.getValue(1).label)
        assertEquals("me", provider.filters.last().reviewerId)
    }

    @Test
    fun `leaving assigned to me reloads without the team queries`() = runBlocking {
        val provider = FakeReviewProvider(me = me, reviews = listOf(review(1)), teams = listOf(ReviewUser("team", "Team")))
        val vm = PullRequestListViewModel(this) { ReviewTarget(provider, repo) }
        vm.setFilters(ListFilters(assignedToMe = true))
        vm.refresh().join()
        assertEquals(listOf("me", "team"), provider.filters.map { it.reviewerId })

        vm.setFilters(ListFilters())
        vm.refresh().join()
        assertNull(provider.filters.last().reviewerId)
        assertTrue(vm.assignments.value.isEmpty())
    }

    // F5 banner

    private val changed = FileChange("/a.cs", "/a.cs", ChangeKind.MODIFIED, "v")

    private suspend fun CoroutineScope.details(store: ReviewMarkStore, revisions: List<Revision>): PullRequestDetailsViewModel {
        val summary = review(7)
        val provider = FakeReviewProvider(
            me = me,
            details = ReviewDetails(summary, "", summary.reviewers),
            revisions = revisions,
            commits = mapOf(1 to listOf(commit("c1")), 2 to listOf(commit("c2"), commit("c1")), 3 to listOf(commit("c3"), commit("c2"), commit("c1"))),
            changesSince = mapOf((3 to 1) to listOf(changed), (2 to 1) to listOf(changed)),
        )
        return PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary, marksStore = store).also { it.refresh().join() }
    }

    private fun commit(id: String) = ReviewCommit(id, "Message $id", "Author", null)

    @Test
    fun `no banner without a last viewed iteration`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val vm = details(store, listOf(r1, r2))
        assertNull(vm.update.value)
        assertEquals(2, store.get("fake/repo-guid/7/me")?.lastViewedIteration)
    }

    @Test
    fun `banner for one new iteration`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/7/me", ReviewMark(lastViewedIteration = 1, lastViewedSourceCommit = "c1"))
        val update = details(store, listOf(r1, r2)).update.value!!

        assertEquals("Updated: iteration 1 → 2", update.title)
        assertEquals(listOf("Iteration 2 · today · push"), update.iterations.map { iterationLine(it) { "today" } })
        assertEquals(listOf("c2"), update.commits?.map { it.id })
        assertEquals("1 new commit", update.commitsText)
        assertEquals("1 file changed", update.filesText)
    }

    @Test
    fun `banner for several iterations, with their reasons`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/7/me", ReviewMark(lastViewedIteration = 1, lastViewedSourceCommit = "c1"))
        val update = details(store, listOf(r1, r2, r3)).update.value!!

        assertEquals("Updated: iteration 1 → 3", update.title)
        assertEquals(
            listOf("Iteration 2 · d · push", "Iteration 3 · d · force-push, rebase"),
            update.iterations.map { iterationLine(it) { "d" } },
        )
        assertEquals("2 new commits", update.commitsText)
        assertEquals("c1", update.fromCommit)
    }

    @Test
    fun `the banner stays across refreshes of the tab until dismissed`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/7/me", ReviewMark(lastViewedIteration = 1, lastViewedSourceCommit = "c1"))
        val vm = details(store, listOf(r1, r2))

        vm.refresh().join()
        assertEquals("Updated: iteration 1 → 2", vm.update.value?.title)
        vm.dismissUpdate()
        vm.refresh().join()
        assertNull(vm.update.value)
    }

    @Test
    fun `opening a pull request reviewed at an older iteration clears the mark`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/7/me", ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1", lastViewedIteration = 1, lastViewedSourceCommit = "c1"))
        val vm = details(store, listOf(r1, r2, r3))

        val mark = store.get("fake/repo-guid/7/me")!!
        assertNull(mark.reviewedIteration)
        assertEquals(1 to 3, mark.updatedFrom to mark.updatedTo)
        assertEquals(setOf(RevisionReason.PUSH, RevisionReason.REBASE, RevisionReason.FORCE_PUSH), mark.updateReasons)
        assertEquals(3, mark.lastViewedIteration)
        assertEquals("Updated: iteration 1 → 3", vm.update.value?.title)
    }

    @Test
    fun `marking on the tab clears an update and is offered back as not reviewed`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        store.set("fake/repo-guid/7/me", ReviewMark(updatedFrom = 1, updatedTo = 2))
        val vm = details(store, listOf(r1, r2))

        assertFalse(vm.isMarkedReviewed())
        assertEquals(ReviewMarks.MarkResult.Marked, vm.markReviewed())
        assertTrue(vm.isMarkedReviewed())
        assertNull(store.get("fake/repo-guid/7/me")?.updatedFrom)
        vm.markNotReviewed()
        assertFalse(vm.isMarkedReviewed())
    }

    @Test
    fun `the since-last-view diff compares the viewed iteration's source with the latest`() {
        assertEquals(DiffSide("/a.cs", "c1") to DiffSide("/a.cs", "c3"), sinceSides(changed, "c1", r3))
        assertEquals(null to DiffSide("/new.cs", "c3"), sinceSides(FileChange(null, "/new.cs", ChangeKind.ADDED), "c1", r3))
    }
}
