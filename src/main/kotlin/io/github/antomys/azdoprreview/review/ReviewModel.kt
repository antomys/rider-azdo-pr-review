package io.github.antomys.azdoprreview.review

import java.time.Instant

/** Provider-neutral review model shared by Azure DevOps and future providers (e.g. GitLab). */

data class RepoRef(val project: String, val repository: String)

data class ReviewRef(val repo: RepoRef, val id: Int)

data class ReviewUser(val id: String, val displayName: String, val uniqueName: String? = null)

enum class ReviewState { OPEN, MERGED, CLOSED }

data class ReviewSummary(
    val ref: ReviewRef,
    val title: String,
    val author: ReviewUser,
    val sourceBranch: String,
    val targetBranch: String,
    val state: ReviewState,
    val isDraft: Boolean,
    val created: Instant,
)

enum class Vote { APPROVED, APPROVED_WITH_SUGGESTIONS, NONE, WAITING_FOR_AUTHOR, REJECTED }

data class Reviewer(val user: ReviewUser, val vote: Vote, val isRequired: Boolean)

data class ReviewDetails(val summary: ReviewSummary, val description: String, val reviewers: List<Reviewer>)

/** `state = null` lists reviews in every state. */
data class ReviewFilter(val state: ReviewState? = ReviewState.OPEN, val authorId: String? = null, val reviewerId: String? = null)

data class Page(val skip: Int = 0, val top: Int = 50)

/** One pushed version of a review (Azure DevOps iteration, GitLab diff version). */
data class Revision(val id: Int, val sourceCommit: String, val targetCommit: String, val baseCommit: String)

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

/**
 * [oldPath] is null for added files, [newPath] is null for deleted files.
 * [version] identifies the file's content in this change (a blob id), so "viewed" marks can expire when it changes.
 */
data class FileChange(val oldPath: String?, val newPath: String?, val kind: ChangeKind, val version: String? = null)

enum class Side { LEFT, RIGHT }

/** Inclusive, 1-based line range on one side of the diff. */
data class LineRange(val side: Side, val start: Int, val end: Int)

/** [lines] is null for a comment on the whole file. */
data class ThreadAnchor(val path: String, val lines: LineRange? = null)

data class ThreadStatus(val id: String, val isResolved: Boolean)

data class ReviewComment(
    val id: Int,
    val parentId: Int?,
    val author: ReviewUser,
    val body: String,
    val created: Instant,
    val isDeleted: Boolean,
    /** [body] with mentions shown as names, for display only. */
    val displayBody: String = body,
)

/**
 * [anchor] is null for a review-level thread. System threads (pushes, votes) have no [status].
 */
data class ReviewThread(
    val id: Int,
    val anchor: ThreadAnchor?,
    val status: ThreadStatus?,
    val isSystem: Boolean,
    val comments: List<ReviewComment>,
)

data class ProviderCapabilities(
    val threadStatuses: List<ThreadStatus>,
    val openStatus: ThreadStatus,
    val resolvedStatus: ThreadStatus,
    val multiLineComments: Boolean,
    val draftReviews: Boolean,
)
