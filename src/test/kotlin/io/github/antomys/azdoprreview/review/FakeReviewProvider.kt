package io.github.antomys.azdoprreview.review

import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider

/** In-memory provider for UI and view-model tests. Unused operations fail loudly. */
class FakeReviewProvider(
    private val me: ReviewUser = ReviewUser("me", "Me"),
    private val reviews: List<ReviewSummary> = emptyList(),
    private val failure: Exception? = null,
) : ReviewProvider {
    val filters = mutableListOf<ReviewFilter>()

    override val id = "fake"
    override val capabilities = AzureDevOpsProvider.CAPABILITIES

    override suspend fun currentUser() = me

    override suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page): List<ReviewSummary> {
        filters += filter
        failure?.let { throw it }
        return reviews
    }

    override fun webUrl(ref: ReviewRef) = "https://example.test/pr/${ref.id}"

    override fun encodeMention(user: ReviewUser) = "@${user.id}"

    override suspend fun getReview(ref: ReviewRef): ReviewDetails = unused()
    override suspend fun listRevisions(ref: ReviewRef): List<Revision> = unused()
    override suspend fun listChanges(ref: ReviewRef, revision: Revision): List<FileChange> = unused()
    override suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray? = unused()
    override suspend fun listThreads(ref: ReviewRef): List<ReviewThread> = unused()
    override suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread = unused()
    override suspend fun reply(ref: ReviewRef, threadId: Int, parentCommentId: Int, body: String): ReviewComment = unused()
    override suspend fun editComment(ref: ReviewRef, threadId: Int, commentId: Int, body: String): ReviewComment = unused()
    override suspend fun deleteComment(ref: ReviewRef, threadId: Int, commentId: Int): Unit = unused()
    override suspend fun setThreadStatus(ref: ReviewRef, threadId: Int, status: ThreadStatus): ReviewThread = unused()
    override suspend fun searchUsers(query: String, limit: Int): List<ReviewUser> = unused()
    override suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser> = unused()

    private fun unused(): Nothing = error("not used by this test")
}
