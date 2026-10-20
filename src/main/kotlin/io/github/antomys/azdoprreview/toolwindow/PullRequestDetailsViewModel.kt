package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.review.Vote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One pull request: details, the files changed in its latest iteration, its threads, and the user's viewed marks.
 * Write operations are suspend functions that throw on failure, so the caller can show the error where it happened.
 */
class PullRequestDetailsViewModel(
    private val scope: CoroutineScope,
    val target: ReviewTarget,
    val summary: ReviewSummary,
    private val viewedFiles: ViewedFiles = ViewedFiles.InMemory(),
) {
    sealed interface State {
        data object Loading : State
        /** [changes] are sorted by directory, then file name: the order of the changes tree and the diff. */
        data class Loaded(val details: ReviewDetails, val revision: Revision, val changes: List<FileChange>, val me: ReviewUser) : State
        data class Failed(val message: String) : State
    }

    /** One side of a file diff: the file at [path] in [commit]. */
    data class DiffSide(val path: String, val commit: String)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Threads with positions in the loaded revision. */
    private val _threads = MutableStateFlow<List<ReviewThread>>(emptyList())
    val threads: StateFlow<List<ReviewThread>> = _threads.asStateFlow()

    private val reviewKey = target.provider.webUrl(summary.ref)
    private val _viewed = MutableStateFlow(viewedFiles.viewed(reviewKey))

    /** Path to the version marked as viewed; compare with [isViewed]. */
    val viewed: StateFlow<Map<String, String>> = _viewed.asStateFlow()

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

    fun webUrl(): String = reviewKey

    fun isViewed(change: FileChange, viewed: Map<String, String> = _viewed.value): Boolean =
        viewed[change.path] == change.version.orEmpty()

    fun setViewed(change: FileChange, isViewed: Boolean) {
        viewedFiles.setViewed(reviewKey, change.path, if (isViewed) change.version.orEmpty() else null)
        _viewed.value = viewedFiles.viewed(reviewKey)
    }

    /** The first unviewed file after [current] in tree order, wrapping around; null when every file is viewed. */
    fun nextUnviewed(current: FileChange?): FileChange? {
        val changes = (state.value as? State.Loaded)?.changes ?: return null
        val start = changes.indexOf(current) + 1
        return (changes.drop(start) + changes.take(start)).firstOrNull { !isViewed(it) }
    }

    suspend fun vote(vote: Vote) {
        target.provider.setVote(summary.ref, vote)
        val details = target.provider.getReview(summary.ref)
        _state.update { if (it is State.Loaded) it.copy(details = details) else it }
    }

    suspend fun createThread(anchor: ThreadAnchor?, body: String) {
        target.provider.createThread(summary.ref, loaded().revision, anchor, body)
        reloadThreads()
    }

    suspend fun reply(thread: ReviewThread, body: String) {
        target.provider.reply(summary.ref, thread.id, thread.comments.first().id, body)
        reloadThreads()
    }

    suspend fun setResolved(thread: ReviewThread, resolved: Boolean) {
        val capabilities = target.provider.capabilities
        target.provider.setThreadStatus(summary.ref, thread.id, if (resolved) capabilities.resolvedStatus else capabilities.openStatus)
        reloadThreads()
    }

    private fun loaded() = state.value as? State.Loaded ?: error("The pull request isn't loaded")

    private suspend fun reloadThreads() {
        _threads.value = target.provider.listThreads(summary.ref, loaded().revision)
    }

    private suspend fun load(): State {
        val provider = target.provider
        val details = provider.getReview(summary.ref)
        val revision = provider.listRevisions(summary.ref).maxByOrNull { it.id }
            ?: return State.Failed("The pull request has no iterations yet")
        val changes = provider.listChanges(summary.ref, revision).sortedWith(compareBy({ it.path.substringBeforeLast('/') }, { it.path }))
        _threads.value = provider.listThreads(summary.ref, revision)
        return State.Loaded(details, revision, changes, provider.currentUser())
    }

    companion object {
        /** Left: the file at the merge base; right: the file in the revision. Null for a side where it doesn't exist. */
        fun diffSides(change: FileChange, revision: Revision): Pair<DiffSide?, DiffSide?> =
            change.oldPath?.let { DiffSide(it, revision.baseCommit) } to change.newPath?.let { DiffSide(it, revision.sourceCommit) }
    }
}

/** The path a change is shown and marked under: the new path, or the old one for deleted files. */
val FileChange.path: String get() = newPath ?: oldPath.orEmpty()
