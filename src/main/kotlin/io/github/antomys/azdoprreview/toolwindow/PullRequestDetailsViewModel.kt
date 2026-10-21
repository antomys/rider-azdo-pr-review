package io.github.antomys.azdoprreview.toolwindow

import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewCommit
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewMarkStore
import io.github.antomys.azdoprreview.review.ReviewMarks
import io.github.antomys.azdoprreview.review.ReviewStatus
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.ui.encode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * One pull request: details, the files changed in its latest iteration, its threads, and the user's viewed marks.
 * Loading it records the latest iteration as the user's last viewed one in [marksStore], and reports what changed since
 * the previous view in [update].
 * Write operations are suspend functions that throw on failure, so the caller can show the error where it happened.
 */
class PullRequestDetailsViewModel(
    private val scope: CoroutineScope,
    val target: ReviewTarget,
    val summary: ReviewSummary,
    private val viewedFiles: ViewedFiles = ViewedFiles.InMemory(),
    private val marksStore: ReviewMarkStore = ReviewMarkStore.InMemory(),
    private val clock: () -> Instant = Instant::now,
) {
    sealed interface State {
        data object Loading : State
        /** [changes] are sorted by directory, then file name: the order of the changes tree and the diff. */
        data class Loaded(val details: ReviewDetails, val revision: Revision, val changes: List<FileChange>, val me: ReviewUser) : State
        data class Failed(val message: String) : State
    }

    /** One side of a file diff: the file at [path] in [commit]. */
    data class DiffSide(val path: String, val commit: String)

    /**
     * What changed between iteration [from], the one the user viewed before, and [latest]: the [iterations] after it,
     * oldest first, and, once loaded, the new [commits] and the [files] changed in between. [fromCommit] is [from]'s
     * source commit, the left side of the "changes since last view" diff.
     */
    data class Update(
        val from: Int,
        val fromCommit: String?,
        val latest: Revision,
        val iterations: List<Revision>,
        val commits: List<ReviewCommit>? = null,
        val files: List<FileChange>? = null,
        val error: String? = null,
    ) {
        val title: String get() = "Updated: iteration $from → ${latest.id}"

        /** "1 new commit", once loaded. */
        val commitsText: String? get() = commits?.let { count(it.size, "new commit") }

        /** "2 files changed", once loaded. */
        val filesText: String? get() = files?.let { count(it.size, "file") + " changed" }
    }

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

    private val _drafts = MutableStateFlow<List<DraftComment>>(emptyList())

    /** Comments written but not sent yet, oldest first; they are sent together with [submitReview]. */
    val drafts: StateFlow<List<DraftComment>> = _drafts.asStateFlow()
    private val draftIds = AtomicLong()

    private val _update = MutableStateFlow<Update?>(null)

    /** What changed since the user last viewed the pull request; null when nothing did, or once dismissed. */
    val update: StateFlow<Update?> = _update.asStateFlow()

    /** The iteration the update is shown from; kept across refreshes of this tab until dismissed. */
    @Volatile
    private var updateFrom: Int? = null

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
            loadUpdate()
        }.also { loading = it }
    }

    fun dismissUpdate() {
        updateFrom = null
        _update.value = null
    }

    /** Whether the user marked this pull request reviewed (locally). */
    fun isMarkedReviewed(): Boolean = marks()?.status(summary) == ReviewStatus.REVIEWED

    /** Marks the pull request reviewed locally; see [ReviewMarks.markReviewed]. Nothing is sent to the service. */
    suspend fun markReviewed(force: Boolean = false): ReviewMarks.MarkResult =
        (marks() ?: error("The pull request isn't loaded")).markReviewed(summary, force)

    fun markNotReviewed() {
        marks()?.markNotReviewed(summary)
    }

    private fun marks(): ReviewMarks? = me()?.let { ReviewMarks(marksStore, target.provider, it.id, clock) }

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

    /** Approve, or approve with suggestions while the user has drafts or open threads they started. */
    fun suggestedVote(): Vote {
        val me = (state.value as? State.Loaded)?.me ?: return Vote.APPROVED
        val openOwnThreads = threads.value.any { !it.isSystem && it.status != null && !it.isResolved && it.comments.firstOrNull()?.author?.id == me.id }
        return if (openOwnThreads || drafts.value.isNotEmpty()) Vote.APPROVED_WITH_SUGGESTIONS else Vote.APPROVED
    }

    /**
     * The thread after (or before, when not [forward]) [line] (1-based) of [file], in file order then line order;
     * null at either end. File-level threads come before a file's line threads.
     */
    fun adjacentThread(file: FileChange?, line: Int, forward: Boolean): ReviewThread? {
        val changes = (state.value as? State.Loaded)?.changes ?: return null
        fun key(index: Int, line: Int) = index.toLong() * Int.MAX_VALUE + line
        val current = key(changes.indexOf(file).coerceAtLeast(0), line)
        val ordered = threads.value.filter { !it.isSystem && it.anchor != null && it.comments.any { c -> !c.isDeleted } }
            .mapNotNull { thread -> changeOf(thread)?.let { key(changes.indexOf(it), thread.anchor?.lines?.start ?: 0) to thread } }
            .sortedBy { it.first }
        return if (forward) ordered.firstOrNull { it.first > current }?.second else ordered.lastOrNull { it.first < current }?.second
    }

    /** The signed-in user, once loaded. */
    fun me(): ReviewUser? = (state.value as? State.Loaded)?.me

    /** Whether the user is one of the reviewers. */
    fun isReviewer(): Boolean {
        val loaded = state.value as? State.Loaded ?: return false
        return loaded.details.reviewers.any { it.user.id == loaded.me.id }
    }

    /** A new thread on [anchor] (or the whole pull request when null), or a reply to [replyTo] when given. */
    fun addDraft(anchor: ThreadAnchor?, replyTo: ReviewThread?, body: String, mentioned: Map<String, ReviewUser> = emptyMap()): DraftComment =
        DraftComment(draftIds.incrementAndGet(), anchor ?: replyTo?.anchor, replyTo?.id, body, mentioned)
            .also { draft -> _drafts.update { it + draft } }

    fun editDraft(draft: DraftComment, body: String, mentioned: Map<String, ReviewUser> = emptyMap()) {
        _drafts.update { drafts -> drafts.map { if (it.id == draft.id) it.copy(body = body, mentioned = mentioned) else it } }
    }

    fun deleteDraft(draft: DraftComment) {
        _drafts.update { drafts -> drafts.filterNot { it.id == draft.id } }
    }

    /** Sends one draft now, without the rest of the review. */
    suspend fun postDraft(draft: DraftComment) {
        send(draft)
        deleteDraft(draft)
        reloadThreads()
    }

    /**
     * Sends every draft in the order they were written, then [comment] as a pull request comment when it isn't blank,
     * then [vote] when given. A draft is removed once it is sent, so after a failure only the unsent ones remain.
     */
    suspend fun submitReview(vote: Vote?, comment: String) {
        try {
            for (draft in drafts.value) {
                send(draft)
                deleteDraft(draft)
            }
            if (comment.isNotBlank()) target.provider.createThread(summary.ref, loaded().revision, null, comment)
        } finally {
            reloadThreads()
        }
        if (vote != null) vote(vote)
    }

    /** Adds the current user as an optional reviewer. */
    suspend fun addMeAsReviewer() = requestReview(loaded().me)

    suspend fun requestReview(user: ReviewUser) {
        target.provider.addReviewer(summary.ref, user)
        reloadDetails()
    }

    suspend fun complete() {
        target.provider.complete(summary.ref)
        reloadDetails()
    }

    suspend fun abandon() {
        target.provider.abandon(summary.ref)
        reloadDetails()
    }

    /** The user's vote, or [Vote.NONE] if they aren't a reviewer. */
    fun myVote(): Vote {
        val loaded = state.value as? State.Loaded ?: return Vote.NONE
        return loaded.details.reviewers.firstOrNull { it.user.id == loaded.me.id }?.vote ?: Vote.NONE
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
        reloadDetails()
        // The vote also adds a system event to the discussion.
        reloadThreads()
    }

    private suspend fun reloadDetails() {
        val details = target.provider.getReview(summary.ref)
        _state.update { if (it is State.Loaded) it.copy(details = details) else it }
    }

    private suspend fun send(draft: DraftComment) {
        val replyTo = draft.replyTo
        val body = encode(draft.body, draft.mentioned, target.provider::encodeMention)
        if (replyTo == null) {
            target.provider.createThread(summary.ref, loaded().revision, draft.anchor, body)
        } else {
            val thread = threads.value.firstOrNull { it.id == replyTo } ?: error("The thread this reply was written for no longer exists")
            target.provider.reply(summary.ref, thread.id, thread.comments.first().id, body)
        }
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
        val revisions = provider.listRevisions(summary.ref)
        val revision = revisions.maxByOrNull { it.id } ?: return State.Failed("The pull request has no iterations yet")
        val changes = provider.listChanges(summary.ref, revision).sortedWith(compareBy({ it.path.substringBeforeLast('/') }, { it.path }))
        _threads.value = provider.listThreads(summary.ref, revision)
        val me = provider.currentUser()
        recordView(me, revisions, revision)
        return State.Loaded(details, revision, changes, me)
    }

    /**
     * Works out the update since the previously recorded view first, then records this view, which also clears a
     * reviewed mark that a new iteration invalidated.
     */
    private fun recordView(me: ReviewUser, revisions: List<Revision>, latest: Revision) {
        val before = ReviewMarks(marksStore, target.provider, me.id, clock).viewed(summary, revisions)
        val from = (updateFrom ?: before?.lastViewedIteration)?.takeIf { it < latest.id }
        updateFrom = from
        _update.value = from?.let {
            Update(
                from = it,
                fromCommit = revisions.firstOrNull { r -> r.id == it }?.sourceCommit ?: before?.lastViewedSourceCommit,
                latest = latest,
                iterations = revisions.filter { r -> r.id > it }.sortedBy { r -> r.id },
            )
        }
    }

    /** The commits and files of [update]: commits in the latest iteration that the viewed one lacks, and the file delta. */
    private suspend fun loadUpdate() {
        val update = _update.value?.takeIf { it.files == null } ?: return
        val loaded = try {
            val seen = target.provider.listCommits(summary.ref, update.from).map { it.id }.toSet()
            val commits = target.provider.listCommits(summary.ref, update.latest.id).filterNot { it.id in seen }
            val files = target.provider.listChanges(summary.ref, update.latest, update.from)
                .sortedWith(compareBy({ it.path.substringBeforeLast('/') }, { it.path }))
            update.copy(commits = commits, files = files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update.copy(error = e.message ?: e.javaClass.simpleName)
        }
        // Dismissed or replaced meanwhile: keep that.
        _update.compareAndSet(update, loaded)
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

        /**
         * For a change listed since an earlier iteration: left, the file at that iteration's source commit [fromCommit];
         * right, the file in [revision].
         */
        fun sinceSides(change: FileChange, fromCommit: String, revision: Revision): Pair<DiffSide?, DiffSide?> =
            change.oldPath?.let { DiffSide(it, fromCommit) } to change.newPath?.let { DiffSide(it, revision.sourceCommit) }

        /** "Iteration 2 · <date> · push": the iteration, when it was created and why, as far as known. */
        fun iterationLine(revision: Revision, formatDate: (Instant) -> String): String =
            listOfNotNull("Iteration ${revision.id}", revision.created?.let(formatDate), revision.reasons.sortedBy { it.ordinal }.joinToString { it.label }.ifEmpty { null })
                .joinToString(" · ")

        fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
    }
}

/** The path a change is shown and marked under: the new path, or the old one for deleted files. */
val FileChange.path: String get() = newPath ?: oldPath.orEmpty()

/**
 * A comment held back until the review is submitted: a new thread on [anchor], or a reply to thread [replyTo].
 * [body] is as typed, with mentions as `@Name`; [mentioned] maps those names to people, for sending.
 */
data class DraftComment(val id: Long, val anchor: ThreadAnchor?, val replyTo: Int?, val body: String, val mentioned: Map<String, ReviewUser> = emptyMap())
