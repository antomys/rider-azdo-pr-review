package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.DiffSide
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

        assertEquals(State.Loaded(details, latest, listOf(edited), ReviewUser("me", "Me")), vm.state.value)
    }

    @Test
    fun `sorts changes by directory, then path`() = runBlocking {
        val files = listOf("/src/b.cs", "/README.md", "/docs/x.md", "/src/a.cs").map { FileChange(it, it, ChangeKind.MODIFIED, "v") }
        val vm = loadedWith(files)
        assertEquals(listOf("/README.md", "/docs/x.md", "/src/a.cs", "/src/b.cs"), (vm.state.value as State.Loaded).changes.map { it.path })
    }

    @Test
    fun `a viewed mark lasts until the file's version changes`() = runBlocking {
        val store = ViewedFiles.InMemory()
        val v1 = FileChange("/a.cs", "/a.cs", ChangeKind.MODIFIED, "v1")
        val vm = loadedWith(listOf(v1), store)

        vm.setViewed(v1, true)
        assertTrue(vm.isViewed(v1))
        assertFalse(vm.isViewed(v1.copy(version = "v2")))
        // Marks are stored per review, so a new view model for the same PR sees them.
        assertTrue(loadedWith(listOf(v1), store).isViewed(v1))

        vm.setViewed(v1, false)
        assertFalse(vm.isViewed(v1))
    }

    @Test
    fun `finds the next unviewed file after the current one, wrapping around`() = runBlocking {
        val (a, b, c) = listOf("/a", "/b", "/c").map { FileChange(it, it, ChangeKind.MODIFIED, "v") }
        val vm = loadedWith(listOf(a, b, c))
        vm.setViewed(b, true)

        assertEquals(a, vm.nextUnviewed(null))
        assertEquals(c, vm.nextUnviewed(a))
        assertEquals(a, vm.nextUnviewed(c))
        listOf(a, c).forEach { vm.setViewed(it, true) }
        assertNull(vm.nextUnviewed(a))
    }

    @Test
    fun `voting reloads the reviewers`() = runBlocking {
        val provider = FakeReviewProvider(details = details, revisions = listOf(latest))
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()

        vm.vote(Vote.APPROVED)

        assertEquals(listOf(Vote.APPROVED), provider.votes)
        assertTrue(vm.state.value is State.Loaded)
    }

    @Test
    fun `new threads are posted on the loaded revision and the list reloads`() = runBlocking {
        val provider = FakeReviewProvider(details = details, revisions = listOf(latest))
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()
        val anchor = ThreadAnchor("/a.cs", LineRange(Side.RIGHT, 3, 4))

        vm.createThread(anchor, "Why?")

        assertEquals(listOf(anchor to "Why?"), provider.createdThreads)
        assertEquals(listOf(anchor), vm.threads.value.map { it.anchor })
    }

    @Test
    fun `suggests approving with suggestions while a thread the user started is open`() = runBlocking {
        val provider = FakeReviewProvider(details = details, revisions = listOf(latest), threads = listOf(thread(1, author = other)))
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()
        assertEquals(Vote.APPROVED, vm.suggestedVote())

        provider.threads += thread(2, author = me)
        vm.refresh().join()
        assertEquals(Vote.APPROVED_WITH_SUGGESTIONS, vm.suggestedVote())

        provider.threads = provider.threads.map { if (it.id == 2) it.copy(status = provider.capabilities.resolvedStatus) else it }
        vm.refresh().join()
        assertEquals(Vote.APPROVED, vm.suggestedVote())
    }

    @Test
    fun `a review with a comment posts the comment, then votes`() = runBlocking {
        val provider = FakeReviewProvider(details = details, revisions = listOf(latest))
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()

        vm.submitReview(Vote.APPROVED, "  ")
        vm.submitReview(Vote.WAITING_FOR_AUTHOR, "Please add tests")

        assertEquals(listOf(null to "Please add tests"), provider.createdThreads)
        assertEquals(listOf(Vote.APPROVED, Vote.WAITING_FOR_AUTHOR), provider.votes)
    }

    @Test
    fun `discussion lists threads by start time, per file on request, with resolved ones on request`() {
        val general = thread(1, created = 30)
        val onA = thread(2, created = 10, anchor = ThreadAnchor("/a.cs"))
        val resolvedOnA = thread(3, created = 20, anchor = ThreadAnchor("/a.cs")).copy(status = FakeReviewProvider().capabilities.resolvedStatus)
        val onB = thread(4, created = 40, anchor = ThreadAnchor("/b.cs"))
        val system = thread(5, created = 0).copy(isSystem = true, status = null)
        val all = listOf(general, onA, resolvedOnA, onB, system)

        assertEquals(listOf(2, 1, 4), PullRequestDetailsViewModel.discussion(all, null, showResolved = false).map { it.id })
        assertEquals(listOf(2, 3, 1, 4), PullRequestDetailsViewModel.discussion(all, null, showResolved = true).map { it.id })
        assertEquals(listOf(2, 3), PullRequestDetailsViewModel.discussion(all, edited, showResolved = true).map { it.id })
    }

    @Test
    fun `mention search puts matching participants before the provider's results`() = runBlocking {
        val provider = FakeReviewProvider(
            details = details, revisions = listOf(latest), threads = listOf(thread(1, author = other)),
            users = listOf(ReviewUser("x", "Ihor Search"), other),
        )
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()

        assertEquals(listOf("o", "x"), vm.searchUsers("ih").map { it.id })
        assertEquals(listOf("u"), vm.searchUsers("user").map { it.id })
    }

    @Test
    fun `mention search keeps the participants when the provider's search fails`() = runBlocking {
        val provider = FakeReviewProvider(
            details = details, revisions = listOf(latest), threads = listOf(thread(1, author = other)),
            searchFailure = IllegalStateException("bad response"),
        )
        val vm = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        vm.refresh().join()

        assertEquals(listOf("o"), vm.searchUsers("ih").map { it.id })
    }

    private val me = ReviewUser("me", "Me")
    private val other = ReviewUser("o", "Other Ihor")

    private fun thread(id: Int, author: ReviewUser = me, created: Long = 0, anchor: ThreadAnchor? = null) = ReviewThread(
        id, anchor, FakeReviewProvider().capabilities.openStatus, false,
        listOf(ReviewComment(1, null, author, "text", Instant.ofEpochSecond(created), false)),
    )

    private suspend fun kotlinx.coroutines.CoroutineScope.loadedWith(
        files: List<FileChange>,
        store: ViewedFiles = ViewedFiles.InMemory(),
    ): PullRequestDetailsViewModel {
        val provider = FakeReviewProvider(details = details, revisions = listOf(latest), changes = mapOf(2 to files))
        return PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary, store).also { it.refresh().join() }
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
