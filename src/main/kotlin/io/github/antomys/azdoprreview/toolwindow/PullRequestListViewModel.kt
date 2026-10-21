package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.Assignment
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewMark
import io.github.antomys.azdoprreview.review.ReviewMarkStore
import io.github.antomys.azdoprreview.review.ReviewMarks
import io.github.antomys.azdoprreview.review.ReviewStatus
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.listAssignedToMe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * What the list shows. [state], [author], [reviewer] and [assignedToMe] are sent to the service; [label] and [search]
 * filter the loaded list. A null value doesn't filter. Reviews marked reviewed are hidden unless [showReviewed].
 */
data class ListFilters(
    val state: ReviewState? = ReviewState.OPEN,
    val author: ReviewUser? = null,
    val reviewer: ReviewUser? = null,
    val label: String? = null,
    val search: String = "",
    /** Reviews where the user or one of their teams is a reviewer; used instead of [reviewer]. */
    val assignedToMe: Boolean = false,
    val showReviewed: Boolean = false,
    /** With [assignedToMe]: also the reviews the user declined. */
    val showDeclined: Boolean = false,
) {
    val isDefault: Boolean get() = this == ListFilters()

    fun matches(review: ReviewSummary): Boolean {
        if (label != null && label !in review.labels) return false
        val text = search.trim().removePrefix("#")
        if (text.isEmpty()) return true
        return review.ref.id.toString() == text || review.title.contains(text, ignoreCase = true) ||
            review.author.displayName.contains(text, ignoreCase = true) || review.sourceBranch.contains(text, ignoreCase = true)
    }
}

/**
 * Loads the pull request list for the current filters, then each pull request's comment count. Also keeps the user's
 * local reviewed marks ([marksStore]) and clears those an update invalidated. Swing-free.
 */
class PullRequestListViewModel(
    private val scope: CoroutineScope,
    private val marksStore: ReviewMarkStore = ReviewMarkStore.InMemory(),
    private val connect: suspend () -> ReviewTarget?,
) {
    sealed interface State {
        data object NotConfigured : State
        data object Loading : State

        /** [reviews] as loaded; [visible] after the client-side filters. */
        data class Loaded(val reviews: List<ReviewSummary>, val visible: List<ReviewSummary> = reviews) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _filters = MutableStateFlow(ListFilters())
    val filters: StateFlow<ListFilters> = _filters.asStateFlow()

    /** Discussion threads per pull request id, filled in after the list loads. */
    private val _commentCounts = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val commentCounts: StateFlow<Map<Int, Int>> = _commentCounts.asStateFlow()

    /** The user's role and vote per pull request id, while [ListFilters.assignedToMe] is on. */
    private val _assignments = MutableStateFlow<Map<Int, Assignment>>(emptyMap())
    val assignments: StateFlow<Map<Int, Assignment>> = _assignments.asStateFlow()

    /** A problem that didn't stop the list from loading, e.g. teams that couldn't be queried. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Changes whenever a review mark changes, here or in a pull request's tab; call [marksChanged] then. */
    val markChanges: StateFlow<Long> get() = marksStore.changes

    /** Stale marks already looked up for pruning in this session. */
    private val pruneChecked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The connection the current list was loaded with. */
    @Volatile
    var target: ReviewTarget? = null
        private set

    @Volatile
    var me: ReviewUser? = null
        private set

    private var loading: Job? = null

    /** Applies [filters]; reloads only when a filter the service applies changed. */
    fun setFilters(filters: ListFilters) {
        val old = _filters.value
        _filters.value = filters
        if (old.state != filters.state || old.author?.id != filters.author?.id || old.reviewer?.id != filters.reviewer?.id ||
            old.assignedToMe != filters.assignedToMe
        ) {
            refresh()
        } else {
            marksChanged()
        }
    }

    /** Applies changed review marks to what the list shows. */
    fun marksChanged() {
        _state.update { if (it is State.Loaded) it.copy(visible = visible(it.reviews)) else it }
    }

    fun mark(review: ReviewSummary): ReviewMark? = marks()?.mark(review)

    fun status(review: ReviewSummary): ReviewStatus = marks()?.status(review) ?: ReviewStatus.NEEDS_REVIEW

    /** Loaded pull requests that the filters match but that are hidden because they are marked reviewed. */
    fun hiddenReviewed(): Int {
        val loaded = state.value as? State.Loaded ?: return 0
        val filters = _filters.value
        if (filters.showReviewed) return 0
        return loaded.reviews.count { filters.matches(it) && status(it) == ReviewStatus.REVIEWED }
    }

    /** Marks [review] reviewed locally; see [ReviewMarks.markReviewed]. Nothing is sent to the service. */
    suspend fun markReviewed(review: ReviewSummary, force: Boolean = false): ReviewMarks.MarkResult =
        (marks() ?: error("The pull request list isn't loaded")).markReviewed(review, force).also { marksChanged() }

    fun markNotReviewed(review: ReviewSummary) {
        marks()?.markNotReviewed(review)
        marksChanged()
    }

    /** Reloads the list, cancelling a load still in progress. */
    fun refresh(): Job {
        loading?.cancel()
        return scope.launch {
            _state.value = State.Loading
            val loaded = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
            _state.value = loaded
            if (loaded is State.Loaded) {
                prune(loaded.reviews)
                loadCommentCounts(loaded.reviews)
            }
        }.also { loading = it }
    }

    /** People to filter by: the current user and the loaded pull requests' authors and reviewers, then the provider's search. */
    suspend fun people(query: String): List<ReviewUser> {
        val loaded = (state.value as? State.Loaded)?.reviews.orEmpty()
        val local = (listOfNotNull(me) + loaded.map { it.author } + loaded.flatMap { r -> r.reviewers.map { it.user } })
            .filter { it.matches(query) }
        val remote = try {
            target?.let { it.provider.searchUsers(it.repo, query, MAX_PEOPLE) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return (local + remote).distinctBy { it.id }.take(MAX_PEOPLE)
    }

    private suspend fun load(): State {
        val previous = this.target
        val target = connect().also { target = it } ?: return State.NotConfigured
        // A new provider means a new sign-in, possibly as someone else.
        val me = this.me.takeIf { target.provider === previous?.provider } ?: target.provider.currentUser().also { this.me = it }
        val filters = _filters.value
        val reviews = if (filters.assignedToMe) {
            val assigned = target.provider.listAssignedToMe(target.repo, ReviewFilter(filters.state, filters.author?.id), me)
            val assignments = assigned.reviews.mapNotNull { r -> Assignment.of(r, me, assigned.teams)?.let { r.ref.id to it } }.toMap()
            _assignments.value = assignments
            _notice.value = if (assigned.complete) null else "Some team assignments couldn't be loaded"
            assigned.reviews.filter { it.ref.id in assignments }
        } else {
            _assignments.value = emptyMap()
            _notice.value = null
            target.provider.listReviews(target.repo, ReviewFilter(filters.state, filters.author?.id, filters.reviewer?.id))
        }
        marks()?.checkForUpdates(reviews)
        return State.Loaded(reviews, visible(reviews))
    }

    /**
     * [reviews] after the client-side filters. "Assigned to me" also hides declined reviews and sorts by review status
     * (needs review, updated since review, reviewed), newest first within each.
     */
    private fun visible(reviews: List<ReviewSummary>): List<ReviewSummary> {
        val filters = _filters.value
        val shown = reviews.filter {
            filters.matches(it) && (filters.showReviewed || status(it) != ReviewStatus.REVIEWED) &&
                (!filters.assignedToMe || filters.showDeclined || _assignments.value[it.ref.id]?.declined != true)
        }
        if (!filters.assignedToMe) return shown
        return shown.sortedWith(compareBy<ReviewSummary> { status(it) }.thenByDescending { it.created })
    }

    private fun marks(): ReviewMarks? {
        val provider = target?.provider ?: return null
        return ReviewMarks(marksStore, provider, (me ?: return null).id)
    }

    private suspend fun prune(reviews: List<ReviewSummary>) {
        val target = target ?: return
        try {
            marks()?.prune(target.repo, reviews, pruneChecked)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pruning is housekeeping; it never fails the list.
        }
    }

    private suspend fun loadCommentCounts(reviews: List<ReviewSummary>) {
        val provider = target?.provider ?: return
        val permits = Semaphore(PARALLEL_COUNT_REQUESTS)
        reviews.filterNot { it.ref.id in _commentCounts.value }.map { review ->
            scope.launch {
                permits.withPermit {
                    val count = try {
                        provider.listThreads(review.ref).count { t -> !t.isSystem && t.comments.any { !it.isDeleted } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@withPermit
                    }
                    _commentCounts.update { it + (review.ref.id to count) }
                }
            }
        }.forEach { it.join() }
    }

    private companion object {
        const val MAX_PEOPLE = 20
        const val PARALLEL_COUNT_REQUESTS = 4
    }
}
