package io.github.antomys.azdoprreview.review

import io.github.antomys.azdoprreview.azdo.AzureDevOpsProvider

/** In-memory provider for UI and view-model tests. Unused operations fail loudly. */
class FakeReviewProvider(
    private val me: ReviewUser = ReviewUser("me", "Me"),
    private val reviews: List<ReviewSummary> = emptyList(),
    private val failure: Exception? = null,
    private val details: ReviewDetails? = null,
    var revisions: List<Revision> = emptyList(),
    private val changes: Map<Int, List<FileChange>> = emptyMap(),
    var threads: List<ReviewThread> = emptyList(),
    private val users: List<ReviewUser> = emptyList(),
    private val searchFailure: Exception? = null,
    /** File bytes by "<commit>:<path>"; a missing key means the file doesn't exist at that commit. */
    private val files: Map<String, ByteArray> = emptyMap(),
    /** Reviews by reviewer id, for reviewer-filtered listings; [reviews] is used for the rest. */
    private val byReviewer: Map<String, List<ReviewSummary>>? = null,
    private val teams: List<ReviewUser> = emptyList(),
    /** Reviewer ids whose listing fails. */
    private val failingReviewers: Set<String> = emptySet(),
    private val commits: Map<Int, List<ReviewCommit>> = emptyMap(),
    /** Changes by (revision, compareTo). */
    private val changesSince: Map<Pair<Int, Int>, List<FileChange>> = emptyMap(),
) : ReviewProvider {
    val filters = mutableListOf<ReviewFilter>()
    var revisionCalls = 0
    val votes = mutableListOf<Vote>()
    val createdThreads = mutableListOf<Pair<ThreadAnchor?, String>>()

    override val id = "fake"
    override val capabilities = AzureDevOpsProvider.CAPABILITIES

    override suspend fun currentUser() = me

    override suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page): List<ReviewSummary> {
        filters += filter
        failure?.let { throw it }
        if (filter.reviewerId in failingReviewers) throw IllegalStateException("HTTP 403")
        return byReviewer?.let { it[filter.reviewerId].orEmpty() } ?: reviews
    }

    override fun webUrl(ref: ReviewRef) = "https://example.test/pr/${ref.id}"

    override fun encodeMention(user: ReviewUser) = "@${user.id}"

    override suspend fun getReview(ref: ReviewRef): ReviewDetails {
        failure?.let { throw it }
        return details ?: unused()
    }

    override suspend fun listRevisions(ref: ReviewRef): List<Revision> {
        revisionCalls++
        return revisions
    }
    override suspend fun listChanges(ref: ReviewRef, revision: Revision) = changes[revision.id].orEmpty()
    override suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray? = files["$commit:$path"]
    override suspend fun listChanges(ref: ReviewRef, revision: Revision, compareTo: Int) = changesSince[revision.id to compareTo].orEmpty()
    override suspend fun listCommits(ref: ReviewRef, revision: Int) = commits[revision].orEmpty()
    override suspend fun myTeams(repo: RepoRef) = teams
    override suspend fun listThreads(ref: ReviewRef, revision: Revision?): List<ReviewThread> = threads
    override suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread {
        createdThreads += anchor to body
        return ReviewThread(createdThreads.size, anchor, capabilities.openStatus, false, emptyList()).also { threads = threads + it }
    }
    override suspend fun reply(ref: ReviewRef, threadId: Int, parentCommentId: Int, body: String): ReviewComment = unused()
    override suspend fun editComment(ref: ReviewRef, threadId: Int, commentId: Int, body: String): ReviewComment = unused()
    override suspend fun deleteComment(ref: ReviewRef, threadId: Int, commentId: Int): Unit = unused()
    override suspend fun setThreadStatus(ref: ReviewRef, threadId: Int, status: ThreadStatus): ReviewThread = unused()
    override suspend fun setVote(ref: ReviewRef, vote: Vote) {
        votes += vote
    }

    override suspend fun addReviewer(ref: ReviewRef, user: ReviewUser): Unit = unused()
    override suspend fun complete(ref: ReviewRef): Unit = unused()
    override suspend fun abandon(ref: ReviewRef): Unit = unused()
    override suspend fun avatar(user: ReviewUser): ByteArray? = null

    override suspend fun searchUsers(repo: RepoRef, query: String, limit: Int): List<ReviewUser> =
        searchFailure?.let { throw it } ?: users.filter { it.matches(query) }.take(limit)
    override suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser> = unused()

    private fun unused(): Nothing = error("not used by this test")
}
