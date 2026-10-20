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

    private val _shownFile = MutableStateFlow<FileChange?>(null)

    /** The file the review's diff shows, if it's open. */
    val shownFile: StateFlow<FileChange?> = _shownFile.asStateFlow()

    private val _pendingNavigation = MutableStateFlow<ReviewThread?>(null)

    /** A thread the diff should scroll to once it shows the thread's file; see [navigateTo]. */
    val pendingNavigation: StateFlow<ReviewThread?> = _pendingNavigation.asStateFlow()

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

    /** Called when the diff shows [change]: showing a file marks it viewed. */
    fun onDiffShown(change: FileChange) {
        setViewed(change, true)
        _shownFile.value = change
    }

    /** The changed file a file thread is on, if it's in the loaded changes. */
    fun changeOf(thread: ReviewThread): FileChange? {
        val path = thread.anchor?.path ?: return null
        return (state.value as? State.Loaded)?.changes?.firstOrNull { it.newPath == path || it.oldPath == path }
    }

    /** Asks the diff to scroll to [thread]; the diff calls [navigated] when it has. */
    fun navigateTo(thread: ReviewThread) {
        _pendingNavigation.value = thread
    }

    fun navigated(thread: ReviewThread) {
        _pendingNavigation.compareAndSet(thread, null)
    }

    /** Approve, or approve with suggestions while threads the user started are still open. */
    fun suggestedVote(): Vote {
        val me = (state.value as? State.Loaded)?.me ?: return Vote.APPROVED
        val openOwnThreads = threads.value.any { !it.isSystem && it.status != null && !it.isResolved && it.comments.firstOrNull()?.author?.id == me.id }
        return if (openOwnThreads) Vote.APPROVED_WITH_SUGGESTIONS else Vote.APPROVED
    }

    /** The user's vote, or [Vote.NONE] if they aren't a reviewer. */
    fun myVote(): Vote {
        val loaded = state.value as? State.Loaded ?: return Vote.NONE
        return loaded.details.reviewers.firstOrNull { it.user.id == loaded.me.id }?.vote ?: Vote.NONE
    }

    /** Posts [comment] as a pull request comment when it isn't blank, then votes. */
    suspend fun submitReview(vote: Vote, comment: String) {
        if (comment.isNotBlank()) createThread(null, comment)
        vote(vote)
    }

    /** People to mention: the review's participants that match [query] first, then the provider's search results. */
    suspend fun searchUsers(query: String): List<ReviewUser> {
        val loaded = state.value as? State.Loaded
        val participants = listOfNotNull(loaded?.details?.summary?.author, loaded?.me) +
            loaded?.details?.reviewers?.map { it.user }.orEmpty() +
            threads.value.filterNot { it.isSystem }.flatMap { t -> t.comments.map { it.author } }
        val local = participants.filter { it.matches(query) }
        // A failed search still leaves the participants to choose from.
        val remote = try {
            target.provider.searchUsers(target.repo, query, MAX_MENTION_RESULTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return (local + remote).distinctBy { it.id }.take(MAX_MENTION_RESULTS)
    }

    fun encodeMention(user: ReviewUser): String = target.provider.encodeMention(user)

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
        private const val MAX_MENTION_RESULTS = 20

        /**
         * Discussion threads (no system threads) in the order they were started; only [file]'s threads when given,
         * and resolved threads only when [showResolved].
         */
        fun discussion(threads: List<ReviewThread>, file: FileChange?, showResolved: Boolean): List<ReviewThread> =
            threads
                .filter { !it.isSystem && it.comments.any { c -> !c.isDeleted } }
                .filter { showResolved || !it.isResolved }
                .filter { file == null || it.anchor?.path.let { p -> p != null && (p == file.newPath || p == file.oldPath) } }
                .sortedBy { it.created }

        /** Left: the file at the merge base; right: the file in the revision. Null for a side where it doesn't exist. */
        fun diffSides(change: FileChange, revision: Revision): Pair<DiffSide?, DiffSide?> =
            change.oldPath?.let { DiffSide(it, revision.baseCommit) } to change.newPath?.let { DiffSide(it, revision.sourceCommit) }
    }
}

/** The path a change is shown and marked under: the new path, or the old one for deleted files. */
val FileChange.path: String get() = newPath ?: oldPath.orEmpty()
