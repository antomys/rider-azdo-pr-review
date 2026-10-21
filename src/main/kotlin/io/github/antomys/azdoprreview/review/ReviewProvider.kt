package io.github.antomys.azdoprreview.review

/** Operations the review UI needs from a code-review host. */
interface ReviewProvider {
    val id: String
    val capabilities: ProviderCapabilities

    suspend fun currentUser(): ReviewUser

    suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page = Page()): List<ReviewSummary>

    suspend fun getReview(ref: ReviewRef): ReviewDetails

    suspend fun listRevisions(ref: ReviewRef): List<Revision>

    /** Changes in [revision] compared with the merge base. */
    suspend fun listChanges(ref: ReviewRef, revision: Revision): List<FileChange>

    /** File bytes at [commit], or null if the file doesn't exist there. */
    suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray?

    suspend fun listThreads(ref: ReviewRef): List<ReviewThread>

    /** Starts a thread on [anchor] as seen in [revision], or a review-level thread when [anchor] is null. */
    suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread

    suspend fun reply(ref: ReviewRef, threadId: Int, parentCommentId: Int, body: String): ReviewComment

    suspend fun editComment(ref: ReviewRef, threadId: Int, commentId: Int, body: String): ReviewComment

    suspend fun deleteComment(ref: ReviewRef, threadId: Int, commentId: Int)

    suspend fun setThreadStatus(ref: ReviewRef, threadId: Int, status: ThreadStatus): ReviewThread

    suspend fun searchUsers(query: String, limit: Int = 10): List<ReviewUser>

    /** Looks up users by id, e.g. to show names for mentions stored as ids. */
    suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser>

    /** Text to insert into a comment body to mention [user]. */
    fun encodeMention(user: ReviewUser): String

    /** Link to the review in the host's web UI. */
    fun webUrl(ref: ReviewRef): String
}

/** The provider and repository the UI works against. */
data class ReviewTarget(val provider: ReviewProvider, val repo: RepoRef)
