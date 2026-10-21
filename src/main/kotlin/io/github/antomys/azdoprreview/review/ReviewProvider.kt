package io.github.antomys.azdoprreview.review

/** Operations the review UI needs from a code-review host. */
interface ReviewProvider {
    val id: String
    val capabilities: ProviderCapabilities

    /** The account the provider is signed in to (the Azure DevOps organization); keys locally stored review state. */
    val organization: String get() = id

    suspend fun currentUser(): ReviewUser

    suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page = Page()): List<ReviewSummary>

    suspend fun getReview(ref: ReviewRef): ReviewDetails

    suspend fun listRevisions(ref: ReviewRef): List<Revision>

    /** Changes in [revision] compared with the merge base. */
    suspend fun listChanges(ref: ReviewRef, revision: Revision): List<FileChange>

    /** Changes in [revision] compared with the earlier revision [compareTo]: only what changed in between. */
    suspend fun listChanges(ref: ReviewRef, revision: Revision, compareTo: Int): List<FileChange>

    /** Every commit in [revision], newest first; a revision's commits include those of earlier revisions. */
    suspend fun listCommits(ref: ReviewRef, revision: Int): List<ReviewCommit>

    /** The current user's teams in [repo]'s project; reviews can be assigned to a team instead of a person. */
    suspend fun myTeams(repo: RepoRef): List<ReviewUser>

    /** File bytes at [commit], or null if the file doesn't exist there. */
    suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray?

    /** Threads with positions moved to [revision] when given, so they line up with that revision's diff. */
    suspend fun listThreads(ref: ReviewRef, revision: Revision? = null): List<ReviewThread>

    /** Starts a thread on [anchor] as seen in [revision], or a review-level thread when [anchor] is null. */
    suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread

    suspend fun reply(ref: ReviewRef, threadId: Int, parentCommentId: Int, body: String): ReviewComment

    suspend fun editComment(ref: ReviewRef, threadId: Int, commentId: Int, body: String): ReviewComment

    suspend fun deleteComment(ref: ReviewRef, threadId: Int, commentId: Int)

    suspend fun setThreadStatus(ref: ReviewRef, threadId: Int, status: ThreadStatus): ReviewThread

    /** Sets the current user's vote, adding them as a reviewer if needed. */
    suspend fun setVote(ref: ReviewRef, vote: Vote)

    /** Adds [user] as an optional reviewer; existing reviewers are left as they are. */
    suspend fun addReviewer(ref: ReviewRef, user: ReviewUser)

    /** Merges the review into its target branch with the host's default options. */
    suspend fun complete(ref: ReviewRef)

    /** Closes the review without merging. */
    suspend fun abandon(ref: ReviewRef)

    /** The user's picture, or null if the host has none. */
    suspend fun avatar(user: ReviewUser): ByteArray?

    /** Users to mention in [repo]'s reviews whose name or unique name [ReviewUser.matches] [query]. */
    suspend fun searchUsers(repo: RepoRef, query: String, limit: Int = 10): List<ReviewUser>

    /** Looks up users by id, e.g. to show names for mentions stored as ids. */
    suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser>

    /** Text to insert into a comment body to mention [user]. */
    fun encodeMention(user: ReviewUser): String

    /** Link to the review in the host's web UI. */
    fun webUrl(ref: ReviewRef): String
}

/** The provider and repository the UI works against. */
data class ReviewTarget(val provider: ReviewProvider, val repo: RepoRef)
