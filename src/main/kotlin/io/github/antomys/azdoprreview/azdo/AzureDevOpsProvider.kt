package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient.Companion.encode
import io.github.antomys.azdoprreview.azdo.AzureDevOpsClient.Companion.url
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.Page
import io.github.antomys.azdoprreview.review.ProviderCapabilities
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewFilter
import io.github.antomys.azdoprreview.review.ReviewProvider
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Reviewer
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.review.ThreadStatus
import io.github.antomys.azdoprreview.review.Vote
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** [ReviewProvider] for Azure DevOps Services, REST api-version 7.1. */
class AzureDevOpsProvider(private val client: AzureDevOpsClient) : ReviewProvider {
    override val id = "azure-devops"
    override val capabilities = CAPABILITIES

    /** Request defaults (commentType, subjectKind, ...) are sent; null fields are left out. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val users = ConcurrentHashMap<String, ReviewUser>()

    /** changeTrackingId by file path, per review and iteration; needed to anchor new threads. */
    private val trackingIds = ConcurrentHashMap<Pair<ReviewRef, Int>, Map<String, Int>>()

    override suspend fun currentUser(): ReviewUser =
        get<ConnectionDataDto>(url(client.orgUrl, "/_apis/connectionData")).authenticatedUser.toUser()

    override suspend fun listReviews(repo: RepoRef, filter: ReviewFilter, page: Page): List<ReviewSummary> =
        get<ListDto<GitPullRequestDto>>(
            url(
                client.orgUrl, repoPath(repo) + "/pullrequests",
                "searchCriteria.status" to filter.state.toAzdo(),
                "searchCriteria.creatorId" to filter.authorId,
                "searchCriteria.reviewerId" to filter.reviewerId,
                "\$top" to page.top,
                "\$skip" to page.skip,
                API,
            ),
        ).value.map { it.toSummary(repo) }

    override suspend fun getReview(ref: ReviewRef): ReviewDetails {
        val pr = get<GitPullRequestDto>(url(client.orgUrl, prPath(ref), API))
        return ReviewDetails(
            summary = pr.toSummary(ref.repo),
            description = pr.description.orEmpty(),
            reviewers = pr.reviewers.map { Reviewer(ReviewUser(it.id, it.displayName ?: it.id, it.uniqueName), voteOf(it.vote), it.isRequired) },
        )
    }

    override suspend fun listRevisions(ref: ReviewRef): List<Revision> =
        get<ListDto<IterationDto>>(url(client.orgUrl, prPath(ref) + "/iterations", API)).value.map {
            Revision(it.id, it.sourceRefCommit.commitId, it.targetRefCommit.commitId, it.commonRefCommit.commitId)
        }

    override suspend fun listChanges(ref: ReviewRef, revision: Revision): List<FileChange> =
        changeEntries(ref, revision.id).map { it.toFileChange() }

    override suspend fun fileContent(repo: RepoRef, path: String, commit: String): ByteArray? = try {
        client.send(
            "GET",
            url(
                client.orgUrl, repoPath(repo) + "/items",
                "path" to path,
                "versionDescriptor.version" to commit,
                "versionDescriptor.versionType" to "commit",
                API,
            ),
            accept = "application/octet-stream",
        )
    } catch (e: AzureDevOpsException) {
        if (e.status == 404) null else throw e
    }

    override suspend fun listThreads(ref: ReviewRef): List<ReviewThread> =
        get<ListDto<ThreadDto>>(url(client.orgUrl, prPath(ref) + "/threads", API)).value
            .filterNot { it.isDeleted }
            .map { it.toThread() }

    override suspend fun createThread(ref: ReviewRef, revision: Revision, anchor: ThreadAnchor?, body: String): ReviewThread {
        val thread = NewThreadDto(
            comments = listOf(NewCommentDto(body)),
            status = CAPABILITIES.openStatus.id,
            threadContext = anchor?.toContext(),
            pullRequestThreadContext = anchor?.let {
                PullRequestThreadContextDto(trackingId(ref, revision.id, it.path), IterationContextDto(revision.id, revision.id))
            },
        )
        return send<NewThreadDto, ThreadDto>("POST", url(client.orgUrl, prPath(ref) + "/threads", API), thread).toThread()
    }

    override suspend fun reply(ref: ReviewRef, threadId: Int, parentCommentId: Int, body: String): ReviewComment =
        send<NewCommentDto, CommentDto>(
            "POST", url(client.orgUrl, prPath(ref) + "/threads/$threadId/comments", API), NewCommentDto(body, parentCommentId),
        ).toComment()

    override suspend fun editComment(ref: ReviewRef, threadId: Int, commentId: Int, body: String): ReviewComment =
        send<CommentUpdateDto, CommentDto>(
            "PATCH", url(client.orgUrl, prPath(ref) + "/threads/$threadId/comments/$commentId", API), CommentUpdateDto(body),
        ).toComment()

    override suspend fun deleteComment(ref: ReviewRef, threadId: Int, commentId: Int) {
        client.send("DELETE", url(client.orgUrl, prPath(ref) + "/threads/$threadId/comments/$commentId", API))
    }

    override suspend fun setThreadStatus(ref: ReviewRef, threadId: Int, status: ThreadStatus): ReviewThread =
        send<ThreadStatusUpdateDto, ThreadDto>(
            "PATCH", url(client.orgUrl, prPath(ref) + "/threads/$threadId", API), ThreadStatusUpdateDto(status.id),
        ).toThread()

    /**
     * Graph subject query is the only identity search verified to match name prefixes (it is a preview API).
     * Its descriptors map to the identity ids used in mentions through Graph storage keys.
     */
    override suspend fun searchUsers(query: String, limit: Int): List<ReviewUser> {
        if (query.isBlank()) return emptyList()
        val subjects = send<SubjectQueryDto, ListDto<GraphSubjectDto>>(
            "POST", url(client.identityUrl, "/_apis/graph/subjectquery", GRAPH_API), SubjectQueryDto(query),
        ).value
        return subjects.take(limit).map { subject ->
            val id = get<StorageKeyDto>(url(client.identityUrl, "/_apis/graph/storagekeys/${encode(subject.descriptor)}", GRAPH_API)).value
            ReviewUser(id, subject.displayName ?: id, subject.mailAddress ?: subject.principalName).also { users[id] = it }
        }
    }

    override suspend fun resolveUsers(ids: Collection<String>): Map<String, ReviewUser> {
        val missing = ids.filterNot(users::containsKey).distinct()
        if (missing.isNotEmpty()) {
            get<ListDto<IdentityDto>>(url(client.identityUrl, "/_apis/identities", "identityIds" to missing.joinToString(","), API))
                .value.forEach { it.toUser() }
        }
        return ids.mapNotNull { id -> users[id]?.let { id to it } }.toMap()
    }

    override fun encodeMention(user: ReviewUser): String = AzureDevOpsMentions.encode(user.id)

    override fun webUrl(ref: ReviewRef): String =
        "${client.orgUrl}/${encode(ref.repo.project)}/_git/${encode(ref.repo.repository)}/pullrequest/${ref.id}"

    private suspend fun changeEntries(ref: ReviewRef, iteration: Int): List<ChangeEntryDto> {
        val entries = mutableListOf<ChangeEntryDto>()
        var skip = 0
        while (true) {
            val page = get<IterationChangesDto>(
                url(client.orgUrl, prPath(ref) + "/iterations/$iteration/changes", "\$top" to MAX_CHANGES_PAGE, "\$skip" to skip, API),
            )
            entries += page.changeEntries
            if (page.nextSkip == 0 && page.nextTop == 0) break
            skip = page.nextSkip
        }
        trackingIds[ref to iteration] = entries.associate { (it.item.path ?: it.originalPath.orEmpty()) to it.changeTrackingId }
        return entries
    }

    private suspend fun trackingId(ref: ReviewRef, iteration: Int, path: String): Int {
        val ids = trackingIds[ref to iteration] ?: changeEntries(ref, iteration).let { trackingIds.getValue(ref to iteration) }
        return ids[path] ?: throw IllegalArgumentException("$path is not changed in iteration $iteration")
    }

    private suspend inline fun <reified T> get(url: String): T =
        json.decodeFromString(client.send("GET", url).decodeToString())

    private suspend inline fun <reified B, reified T> send(method: String, url: String, body: B): T =
        json.decodeFromString(client.send(method, url, json.encodeToString(body)).decodeToString())

    private fun IdentityRefDto.toUser() = ReviewUser(id, displayName ?: id, uniqueName).also { users[id] = it }

    private fun IdentityDto.toUser() = ReviewUser(id, customDisplayName ?: providerDisplayName ?: id).also { users[id] = it }

    private fun GitPullRequestDto.toSummary(repo: RepoRef) = ReviewSummary(
        ref = ReviewRef(repo, pullRequestId),
        title = title,
        author = createdBy.toUser(),
        sourceBranch = sourceRefName.removePrefix(HEADS),
        targetBranch = targetRefName.removePrefix(HEADS),
        state = when (status) {
            "completed" -> ReviewState.MERGED
            "abandoned" -> ReviewState.CLOSED
            else -> ReviewState.OPEN
        },
        isDraft = isDraft,
        created = Instant.parse(creationDate),
    )

    private fun ThreadDto.toThread() = ReviewThread(
        id = id,
        anchor = threadContext?.toAnchor(),
        status = status?.let(::statusOf),
        isSystem = comments.firstOrNull()?.commentType == "system",
        comments = comments.map { it.toComment() },
    )

    private fun CommentDto.toComment() = ReviewComment(
        id = id,
        parentId = parentCommentId.takeIf { it != 0 },
        author = author.toUser(),
        body = content.orEmpty(),
        created = Instant.parse(publishedDate),
        isDeleted = isDeleted,
    )

    companion object {
        private val API = "api-version" to "7.1"
        private val GRAPH_API = "api-version" to "7.1-preview.1"
        private const val HEADS = "refs/heads/"
        private const val MAX_CHANGES_PAGE = 2000

        /** Numeric values 1-6 map to these names in this order (verified against the service). */
        val STATUSES = listOf(
            ThreadStatus("active", isResolved = false),
            ThreadStatus("fixed", isResolved = true),
            ThreadStatus("wontFix", isResolved = true),
            ThreadStatus("closed", isResolved = true),
            ThreadStatus("byDesign", isResolved = true),
            ThreadStatus("pending", isResolved = false),
        )

        val CAPABILITIES = ProviderCapabilities(
            threadStatuses = STATUSES,
            openStatus = STATUSES[0],
            resolvedStatus = STATUSES[1],
            multiLineComments = true,
            draftReviews = false,
        )

        private fun statusOf(id: String) = STATUSES.firstOrNull { it.id == id } ?: ThreadStatus(id, isResolved = false)

        private fun ReviewState?.toAzdo() = when (this) {
            ReviewState.OPEN -> "active"
            ReviewState.MERGED -> "completed"
            ReviewState.CLOSED -> "abandoned"
            null -> "all"
        }

        private fun voteOf(vote: Int) = when {
            vote >= 10 -> Vote.APPROVED
            vote >= 5 -> Vote.APPROVED_WITH_SUGGESTIONS
            vote <= -10 -> Vote.REJECTED
            vote <= -5 -> Vote.WAITING_FOR_AUTHOR
            else -> Vote.NONE
        }

        private fun repoPath(repo: RepoRef) = "/${encode(repo.project)}/_apis/git/repositories/${encode(repo.repository)}"

        private fun prPath(ref: ReviewRef) = repoPath(ref.repo) + "/pullRequests/${ref.id}"

        private fun ChangeEntryDto.toFileChange(): FileChange {
            val types = changeType.split(",").map { it.trim() }
            return when {
                "delete" in types -> FileChange(oldPath = originalPath ?: item.path, newPath = null, kind = ChangeKind.DELETED)
                "add" in types -> FileChange(oldPath = null, newPath = item.path, kind = ChangeKind.ADDED)
                "rename" in types -> FileChange(oldPath = originalPath, newPath = item.path, kind = ChangeKind.RENAMED)
                else -> FileChange(oldPath = item.path, newPath = item.path, kind = ChangeKind.MODIFIED)
            }
        }

        private fun ThreadContextDto.toAnchor(): ThreadAnchor {
            val lines = when {
                rightFileStart != null -> LineRange(Side.RIGHT, rightFileStart.line, (rightFileEnd ?: rightFileStart).line)
                leftFileStart != null -> LineRange(Side.LEFT, leftFileStart.line, (leftFileEnd ?: leftFileStart).line)
                else -> null
            }
            return ThreadAnchor(filePath, lines)
        }

        /** Offsets are 1, matching what the service returns for line comments created through the API. */
        private fun ThreadAnchor.toContext(): ThreadContextDto {
            fun pos(side: Side, line: (LineRange) -> Int) = lines?.takeIf { it.side == side }?.let { FilePositionDto(line(it), 1) }
            return ThreadContextDto(
                filePath = path,
                leftFileStart = pos(Side.LEFT) { it.start },
                leftFileEnd = pos(Side.LEFT) { it.end },
                rightFileStart = pos(Side.RIGHT) { it.start },
                rightFileEnd = pos(Side.RIGHT) { it.end },
            )
        }
    }
}
