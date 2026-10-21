package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
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

/**
 * What the list shows. [state], [author] and [reviewer] are sent to the service; [label] and [search] filter the loaded
 * list. A null value doesn't filter.
 */
data class ListFilters(
    val state: ReviewState? = ReviewState.OPEN,
    val author: ReviewUser? = null,
    val reviewer: ReviewUser? = null,
    val label: String? = null,
    val search: String = "",
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

/** Loads the pull request list for the current filters, then each pull request's comment count. Swing-free. */
class PullRequestListViewModel(
    private val scope: CoroutineScope,
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
        if (old.state != filters.state || old.author?.id != filters.author?.id || old.reviewer?.id != filters.reviewer?.id) {
            refresh()
        } else {
            _state.update { if (it is State.Loaded) it.copy(visible = it.reviews.filter(filters::matches)) else it }
        }
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
            if (loaded is State.Loaded) loadCommentCounts(loaded.reviews)
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
        if (me == null || target.provider !== previous?.provider) me = target.provider.currentUser()
        val filters = _filters.value
        val reviews = target.provider.listReviews(target.repo, ReviewFilter(filters.state, filters.author?.id, filters.reviewer?.id))
        return State.Loaded(reviews, reviews.filter(filters::matches))
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
