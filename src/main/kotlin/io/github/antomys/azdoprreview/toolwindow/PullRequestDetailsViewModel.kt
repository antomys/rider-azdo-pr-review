package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads one pull request's details and the files changed in its latest iteration. */
class PullRequestDetailsViewModel(
    private val scope: CoroutineScope,
    val target: ReviewTarget,
    val summary: ReviewSummary,
) {
    sealed interface State {
        data object Loading : State
        data class Loaded(val details: ReviewDetails, val revision: Revision, val changes: List<FileChange>) : State
        data class Failed(val message: String) : State
    }

    /** One side of a file diff: the file at [path] in [commit]. */
    data class DiffSide(val path: String, val commit: String)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private var loading: Job? = null

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

    fun webUrl(): String = target.provider.webUrl(summary.ref)

    private suspend fun load(): State {
        val details = target.provider.getReview(summary.ref)
        val revision = target.provider.listRevisions(summary.ref).maxByOrNull { it.id }
            ?: return State.Failed("The pull request has no iterations yet")
        return State.Loaded(details, revision, target.provider.listChanges(summary.ref, revision))
    }

    companion object {
        /** Left: the file at the merge base; right: the file in the revision. Null for a side where it doesn't exist. */
        fun diffSides(change: FileChange, revision: Revision): Pair<DiffSide?, DiffSide?> =
            change.oldPath?.let { DiffSide(it, revision.baseCommit) } to change.newPath?.let { DiffSide(it, revision.sourceCommit) }
    }
}
