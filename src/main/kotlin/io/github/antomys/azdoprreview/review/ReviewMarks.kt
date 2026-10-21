package io.github.antomys.azdoprreview.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What one user recorded locally about one review: the revision they marked reviewed and the one they last viewed.
 * [updatedFrom] and [updatedTo] remember a reviewed mark that a later revision cleared, with that update's [updateReasons],
 * for the "Updated since review" state. [touched] is when the record last changed; it is used only to prune old records.
 * Comparisons never use time.
 */
data class ReviewMark(
    val reviewedIteration: Int? = null,
    val reviewedSourceCommit: String? = null,
    val lastViewedIteration: Int? = null,
    val lastViewedSourceCommit: String? = null,
    /** Informational only. */
    val lastViewedAt: Instant? = null,
    val updatedFrom: Int? = null,
    val updatedTo: Int? = null,
    val updateReasons: Set<RevisionReason> = emptySet(),
    val touched: Instant = Instant.EPOCH,
) {
    val status: ReviewStatus
        get() = when {
            reviewedIteration != null -> ReviewStatus.REVIEWED
            updatedFrom != null -> ReviewStatus.UPDATED
            else -> ReviewStatus.NEEDS_REVIEW
        }

    /** This mark once the review's latest revision is [latest]: a reviewed mark the update invalidated is cleared. */
    fun updatedTo(latest: Revision, revisions: List<Revision>): ReviewMark {
        val reviewed = reviewedIteration ?: return this
        if (!changed(this, latest.id, latest.sourceCommit)) return this
        return copy(
            reviewedIteration = null,
            reviewedSourceCommit = null,
            updatedFrom = reviewed,
            updatedTo = latest.id,
            updateReasons = revisions.filter { it.id > reviewed }.flatMap { it.reasons }.toSet(),
        )
    }

    /** One line of `name=value` pairs, for the settings file. */
    fun encode(): String = listOfNotNull(
        reviewedIteration?.let { "ri=$it" },
        reviewedSourceCommit?.let { "rc=$it" },
        lastViewedIteration?.let { "vi=$it" },
        lastViewedSourceCommit?.let { "vc=$it" },
        lastViewedAt?.let { "va=$it" },
        updatedFrom?.let { "uf=$it" },
        updatedTo?.let { "ut=$it" },
        updateReasons.takeIf { it.isNotEmpty() }?.let { r -> "ur=" + r.joinToString(",") { it.name } },
        "t=$touched",
    ).joinToString(";")

    companion object {
        /**
         * Whether a review marked reviewed has changed since: a newer revision, or a different source commit (a retarget
         * creates a revision without one). False when nothing is marked reviewed.
         */
        fun changed(mark: ReviewMark, latestIteration: Int, latestSourceCommit: String): Boolean {
            val reviewed = mark.reviewedIteration ?: return false
            return latestIteration > reviewed || latestSourceCommit != mark.reviewedSourceCommit
        }

        /** Reads [encode]'s format; unknown names are ignored, and a value that doesn't parse drops the record. */
        fun decode(text: String): ReviewMark? = runCatching {
            val values = text.split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            ReviewMark(
                reviewedIteration = values["ri"]?.toInt(),
                reviewedSourceCommit = values["rc"],
                lastViewedIteration = values["vi"]?.toInt(),
                lastViewedSourceCommit = values["vc"],
                lastViewedAt = values["va"]?.let(Instant::parse),
                updatedFrom = values["uf"]?.toInt(),
                updatedTo = values["ut"]?.toInt(),
                updateReasons = values["ur"]?.split(',')?.map(RevisionReason::valueOf)?.toSet().orEmpty(),
                touched = values["t"]?.let(Instant::parse) ?: Instant.EPOCH,
            )
        }.getOrNull()
    }
}

/** Where a review stands for the user, in the order "Assigned to me" sorts by. */
enum class ReviewStatus { NEEDS_REVIEW, UPDATED, REVIEWED }

/** Review marks by key `{organization}/{repositoryId}/{pullRequestId}/{userId}`. */
interface ReviewMarkStore {
    fun get(key: String): ReviewMark?

    /** Stores [mark], or removes the record when it is null. */
    fun set(key: String, mark: ReviewMark?)

    fun all(): Map<String, ReviewMark>

    /** Counts changes, so views showing marks can refresh. */
    val changes: StateFlow<Long>

    class InMemory : ReviewMarkStore {
        private val marks = ConcurrentHashMap<String, ReviewMark>()
        private val _changes = MutableStateFlow(0L)
        override val changes: StateFlow<Long> = _changes.asStateFlow()

        override fun get(key: String) = marks[key]

        override fun set(key: String, mark: ReviewMark?) {
            if (mark == null) marks.remove(key) else marks[key] = mark
            _changes.value++
        }

        override fun all(): Map<String, ReviewMark> = marks.toMap()
    }
}

/**
 * The reviewed and last-viewed marks of user [userId] (F3–F5). They are kept locally and never sent to the host:
 * the provider is only read from.
 */
class ReviewMarks(
    private val store: ReviewMarkStore,
    private val provider: ReviewProvider,
    private val userId: String,
    private val clock: () -> Instant = Instant::now,
) {
    /** The result of [markReviewed]. */
    sealed interface MarkResult {
        data object Marked : MarkResult

        /** Not marked: the review has a revision newer than the one the user last viewed. */
        data class UpdatedSinceViewed(val viewed: Int, val latest: Int) : MarkResult
    }

    fun key(review: ReviewSummary) =
        "${provider.organization}/${review.repositoryId ?: review.ref.repo.repository}/${review.ref.id}/$userId"

    fun mark(review: ReviewSummary): ReviewMark? = store.get(key(review))

    fun status(review: ReviewSummary): ReviewStatus = mark(review)?.status ?: ReviewStatus.NEEDS_REVIEW

    /**
     * Marks the latest revision reviewed, as fetched now. Unless [force], a revision newer than the last viewed one
     * returns [MarkResult.UpdatedSinceViewed] instead, so the user can look at the update first.
     */
    suspend fun markReviewed(review: ReviewSummary, force: Boolean = false): MarkResult {
        val latest = provider.listRevisions(review.ref).maxByOrNull { it.id } ?: error("The pull request has no iterations yet")
        val mark = mark(review) ?: ReviewMark()
        val viewed = mark.lastViewedIteration
        if (!force && viewed != null && latest.id > viewed) return MarkResult.UpdatedSinceViewed(viewed, latest.id)
        save(review, mark.copy(reviewedIteration = latest.id, reviewedSourceCommit = latest.sourceCommit).withoutUpdate())
        return MarkResult.Marked
    }

    fun markNotReviewed(review: ReviewSummary) {
        val mark = mark(review) ?: return
        save(review, mark.copy(reviewedIteration = null, reviewedSourceCommit = null).withoutUpdate())
    }

    /**
     * Records that the user is looking at the review's latest revision, clearing a reviewed mark that an update
     * invalidated. Returns the mark as it was before, so the caller can show what changed since the last view.
     */
    fun viewed(review: ReviewSummary, revisions: List<Revision>): ReviewMark? {
        val latest = revisions.maxByOrNull { it.id } ?: return mark(review)
        val before = mark(review)
        val after = (before ?: ReviewMark()).updatedTo(latest, revisions)
            .copy(lastViewedIteration = latest.id, lastViewedSourceCommit = latest.sourceCommit, lastViewedAt = clock())
        save(review, after)
        return before
    }

    /**
     * Clears reviewed marks of [reviews] that changed. The listed source commit is a cheap hint: only when it differs
     * from the reviewed one are the revisions fetched to decide. A review whose revisions can't be loaded is skipped.
     */
    suspend fun checkForUpdates(reviews: List<ReviewSummary>) {
        for (review in reviews) {
            val mark = mark(review) ?: continue
            val listed = review.sourceCommit ?: continue
            if (mark.reviewedIteration == null || listed == mark.reviewedSourceCommit) continue
            val revisions = try {
                provider.listRevisions(review.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val latest = revisions.maxByOrNull { it.id } ?: continue
            val updated = mark.updatedTo(latest, revisions)
            if (updated != mark) save(review, updated)
        }
    }

    /**
     * Removes this user's records in [repo] untouched for [PRUNE_AFTER] whose review is completed or abandoned. The state
     * comes from [loaded] when listed there, otherwise from the provider; keys in [checked] are not asked again.
     */
    suspend fun prune(repo: RepoRef, loaded: List<ReviewSummary>, checked: MutableSet<String>) {
        val repositoryId = loaded.firstNotNullOfOrNull { it.repositoryId } ?: return
        val prefix = "${provider.organization}/$repositoryId/"
        val cutoff = clock().minus(PRUNE_AFTER)
        val stale = store.all().filter { (key, mark) -> key.startsWith(prefix) && key.endsWith("/$userId") && mark.touched < cutoff }
        for (key in stale.keys) {
            val id = key.removePrefix(prefix).substringBefore('/').toIntOrNull() ?: continue
            val state = loaded.firstOrNull { it.ref.id == id }?.state ?: run {
                if (!checked.add(key)) return@run null
                try {
                    provider.getReview(ReviewRef(repo, id)).summary.state
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            if (state != null && state != ReviewState.OPEN) store.set(key, null)
        }
    }

    private fun save(review: ReviewSummary, mark: ReviewMark) = store.set(key(review), mark.copy(touched = clock()))

    private fun ReviewMark.withoutUpdate() = copy(updatedFrom = null, updatedTo = null, updateReasons = emptySet())

    companion object {
        val PRUNE_AFTER: Duration = Duration.ofDays(90)
    }
}
