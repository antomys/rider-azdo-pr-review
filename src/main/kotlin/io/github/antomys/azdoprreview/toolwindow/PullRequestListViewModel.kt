package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class QuickFilter(val label: String) {
    ALL("Everyone's"),
    CREATED_BY_ME("Created by me"),
    ASSIGNED_TO_ME("Assigned to me"),
}

/** Loads the pull request list for the current filters. Swing-free so it can be unit-tested. */
class PullRequestListViewModel(
    private val scope: CoroutineScope,
    private val connect: suspend () -> ReviewTarget?,
) {
    sealed interface State {
        data object NotConfigured : State
        data object Loading : State
        data class Loaded(val reviews: List<ReviewSummary>) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** null lists pull requests in every state. */
    @Volatile
    var reviewState: ReviewState? = ReviewState.OPEN

    @Volatile
    var quickFilter = QuickFilter.ALL

    @Volatile
    private var target: ReviewTarget? = null
    private var loading: Job? = null

    /** Reloads the list, cancelling a load still in progress. */
    fun refresh(): Job {
        loading?.cancel()
        return scope.launch {
            _state.value = State.Loading
            _state.value = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }.also { loading = it }
    }

    fun webUrl(review: ReviewSummary): String? = target?.provider?.webUrl(review.ref)

    private suspend fun load(): State {
        val target = connect().also { target = it } ?: return State.NotConfigured
        val me = if (quickFilter == QuickFilter.ALL) null else target.provider.currentUser().id
        val filter = ReviewFilter(
            state = reviewState,
            authorId = me.takeIf { quickFilter == QuickFilter.CREATED_BY_ME },
            reviewerId = me.takeIf { quickFilter == QuickFilter.ASSIGNED_TO_ME },
        )
        return State.Loaded(target.provider.listReviews(target.repo, filter))
    }
}
