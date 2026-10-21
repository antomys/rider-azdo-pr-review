package io.github.antomys.azdoprreview.review

import io.github.antomys.azdoprreview.review.ReviewMarks.MarkResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ReviewMarksTest {
    private val repo = RepoRef("project", "repo")
    private val review = ReviewSummary(
        ReviewRef(repo, 5), "Title", ReviewUser("u", "User"), "feature", "main", ReviewState.OPEN, false, Instant.EPOCH,
        repositoryId = "repo-guid", sourceCommit = "c1",
    )
    private val first = Revision(1, "c1", "t", "b", reasons = setOf(RevisionReason.PUSH))
    private val second = Revision(2, "c2", "t", "b", reasons = setOf(RevisionReason.FORCE_PUSH))
    private val now = Instant.parse("2026-10-21T10:00:00Z")

    private fun marks(provider: ReviewProvider, store: ReviewMarkStore, user: String = "me", clock: Instant = now) =
        ReviewMarks(store, provider, user, clock = { clock })

    // F4: the pure clearing rule.

    @Test
    fun `same iteration and commit is not a change`() {
        assertFalse(ReviewMark.changed(ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1"), 1, "c1"))
    }

    @Test
    fun `a higher iteration is a change`() {
        assertTrue(ReviewMark.changed(ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1"), 2, "c1"))
    }

    @Test
    fun `the same iteration with a different commit is a change`() {
        assertTrue(ReviewMark.changed(ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1"), 1, "c9"))
    }

    @Test
    fun `without a reviewed mark nothing is cleared`() {
        val viewedOnly = ReviewMark(lastViewedIteration = 1, lastViewedSourceCommit = "c1")
        assertFalse(ReviewMark.changed(viewedOnly, 2, "c2"))
        assertSame(viewedOnly, viewedOnly.updatedTo(second, listOf(first, second)))
    }

    @Test
    fun `an update clears the reviewed mark, keeps the last viewed iteration and records why`() {
        val mark = ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1", lastViewedIteration = 1, lastViewedSourceCommit = "c1")
        val updated = mark.updatedTo(second, listOf(first, second))

        assertEquals(
            mark.copy(reviewedIteration = null, reviewedSourceCommit = null, updatedFrom = 1, updatedTo = 2, updateReasons = setOf(RevisionReason.FORCE_PUSH)),
            updated,
        )
        assertEquals(ReviewStatus.UPDATED, updated.status)
    }

    @Test
    fun `marks survive encoding`() {
        val mark = ReviewMark(1, "c1", 2, "c2", now, 1, 2, setOf(RevisionReason.REBASE, RevisionReason.RETARGET), now)
        assertEquals(mark, ReviewMark.decode(mark.encode()))
        assertEquals(ReviewMark(touched = now), ReviewMark.decode(ReviewMark(touched = now).encode()))
        assertNull(ReviewMark.decode("ri=x"))
    }

    // F3: marking.

    @Test
    fun `marking stores the latest iteration and its source commit`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val marks = marks(FakeReviewProvider(revisions = listOf(first, second)), store)

        assertEquals(MarkResult.Marked, marks.markReviewed(review))
        assertEquals(ReviewMark(reviewedIteration = 2, reviewedSourceCommit = "c2", touched = now), store.get("fake/repo-guid/5/me"))
        assertEquals(ReviewStatus.REVIEWED, marks.status(review))

        marks.markNotReviewed(review)
        assertEquals(ReviewStatus.NEEDS_REVIEW, marks.status(review))
    }

    @Test
    fun `marking warns when the latest iteration is newer than the last viewed one`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val provider = FakeReviewProvider(revisions = listOf(first))
        val marks = marks(provider, store)
        marks.viewed(review, listOf(first))
        provider.revisions = listOf(first, second)

        assertEquals(MarkResult.UpdatedSinceViewed(viewed = 1, latest = 2), marks.markReviewed(review))
        assertEquals(ReviewStatus.NEEDS_REVIEW, marks.status(review))
        assertEquals(MarkResult.Marked, marks.markReviewed(review, force = true))
        assertEquals(2, marks.mark(review)?.reviewedIteration)
    }

    @Test
    fun `marks are kept per user and per organization`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val provider = FakeReviewProvider(revisions = listOf(first))
        marks(provider, store, user = "me").markReviewed(review)

        assertEquals(ReviewStatus.NEEDS_REVIEW, marks(provider, store, user = "someone-else").status(review))
        val otherOrganization = object : ReviewProvider by provider {
            override val organization = "other-org"
        }
        assertEquals(ReviewStatus.NEEDS_REVIEW, marks(otherOrganization, store).status(review))
        assertEquals(setOf("fake/repo-guid/5/me"), store.all().keys)
    }

    @Test
    fun `viewing returns the previous mark and records the latest iteration`() {
        val store = ReviewMarkStore.InMemory()
        val marks = marks(FakeReviewProvider(), store)

        assertNull(marks.viewed(review, listOf(first)))
        assertEquals(1, marks.viewed(review, listOf(first, second))?.lastViewedIteration)
        assertEquals(2, marks.mark(review)?.lastViewedIteration)
        assertEquals("c2", marks.mark(review)?.lastViewedSourceCommit)
    }

    // F4: the cheap list check.

    @Test
    fun `an unchanged listed commit costs no iterations call`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val provider = FakeReviewProvider(revisions = listOf(first))
        val marks = marks(provider, store)
        marks.markReviewed(review)
        provider.revisionCalls = 0

        marks.checkForUpdates(listOf(review))

        assertEquals(0, provider.revisionCalls)
        assertEquals(ReviewStatus.REVIEWED, marks.status(review))
    }

    @Test
    fun `a new listed commit is confirmed with one iterations call and clears the mark`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val provider = FakeReviewProvider(revisions = listOf(first))
        val marks = marks(provider, store)
        marks.markReviewed(review)
        provider.revisions = listOf(first, second)
        provider.revisionCalls = 0

        marks.checkForUpdates(listOf(review.copy(sourceCommit = "c2")))

        assertEquals(1, provider.revisionCalls)
        assertEquals(ReviewStatus.UPDATED, marks.status(review))
        assertEquals(2, marks.mark(review)?.updatedTo)
    }

    @Test
    fun `results don't depend on the clock`() = runBlocking {
        val results = listOf(Instant.EPOCH, now, now.plus(Duration.ofDays(3650))).map { clock ->
            val store = ReviewMarkStore.InMemory()
            val provider = FakeReviewProvider(revisions = listOf(first))
            val marks = marks(provider, store, clock = clock)
            marks.markReviewed(review)
            provider.revisions = listOf(first, second)
            marks.checkForUpdates(listOf(review.copy(sourceCommit = "c2")))
            marks.mark(review)?.copy(touched = Instant.EPOCH)
        }
        assertEquals(1, results.distinct().size)
    }

    // Pruning (D27).

    @Test
    fun `prunes completed or abandoned reviews untouched for 90 days`() = runBlocking {
        val store = ReviewMarkStore.InMemory()
        val old = ReviewMark(reviewedIteration = 1, reviewedSourceCommit = "c1", touched = now.minus(Duration.ofDays(91)))
        val recent = old.copy(touched = now.minus(Duration.ofDays(10)))
        store.set("fake/repo-guid/1/me", old) // completed, listed
        store.set("fake/repo-guid/2/me", old) // open, listed
        store.set("fake/repo-guid/3/me", recent) // completed, but recent
        store.set("fake/repo-guid/4/me", old) // not listed: looked up, abandoned
        store.set("fake/repo-guid/1/other", old) // another user's
        store.set("fake/other-repo/1/me", old) // another repository
        val listed = listOf(
            review.copy(ref = ReviewRef(repo, 1), state = ReviewState.MERGED),
            review.copy(ref = ReviewRef(repo, 2)),
            review.copy(ref = ReviewRef(repo, 3), state = ReviewState.MERGED),
        )
        val abandoned = review.copy(ref = ReviewRef(repo, 4), state = ReviewState.CLOSED)
        val provider = FakeReviewProvider(details = ReviewDetails(abandoned, "", emptyList()))
        val checked = mutableSetOf<String>()

        marks(provider, store).prune(repo, listed, checked)

        assertEquals(
            setOf("fake/repo-guid/2/me", "fake/repo-guid/3/me", "fake/repo-guid/1/other", "fake/other-repo/1/me"),
            store.all().keys,
        )
        assertEquals(setOf("fake/repo-guid/4/me"), checked)
        assertNotEquals(0L, store.changes.value)
    }
}
