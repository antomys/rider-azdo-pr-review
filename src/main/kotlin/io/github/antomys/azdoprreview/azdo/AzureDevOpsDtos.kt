package io.github.antomys.azdoprreview.azdo

import kotlinx.serialization.Serializable

/*
 * Azure DevOps REST 7.1 payloads, reduced to the fields the plugin uses.
 * Shapes checked against responses recorded from a sandbox PR (src/test/resources/fixtures/azdo).
 */

@Serializable
data class ListDto<T>(val value: List<T>, val count: Int? = null)

@Serializable
data class IdentityRefDto(val id: String, val displayName: String? = null, val uniqueName: String? = null)

@Serializable
data class ReviewerDto(
    val id: String,
    val displayName: String? = null,
    val uniqueName: String? = null,
    val vote: Int = 0,
    val isRequired: Boolean = false,
    val hasDeclined: Boolean = false,
    /** Group reviewers (teams) this reviewer's vote counts for. */
    val votedFor: List<IdentityRefDto>? = null,
)

@Serializable
data class GitPullRequestDto(
    val pullRequestId: Int,
    val status: String,
    val title: String,
    val description: String? = null,
    val createdBy: IdentityRefDto,
    val creationDate: String,
    val sourceRefName: String,
    val targetRefName: String,
    val isDraft: Boolean = false,
    val reviewers: List<ReviewerDto> = emptyList(),
    val labels: List<LabelDto>? = null,
    /** "succeeded", "conflicts", "queued", ... */
    val mergeStatus: String? = null,
    val lastMergeSourceCommit: CommitRefDto? = null,
    val repository: RepositoryRefDto? = null,
)

@Serializable
data class RepositoryRefDto(val id: String)

/** An entry of `GET {org}/_apis/git/repositories`, which lists every project's repositories the token can read. */
@Serializable
data class GitRepositoryDto(val name: String, val project: ProjectRefDto, val isDisabled: Boolean? = null)

@Serializable
data class ProjectRefDto(val name: String)

@Serializable
data class LabelDto(val name: String, val active: Boolean = true)

@Serializable
data class PullRequestStatusUpdateDto(val status: String, val lastMergeSourceCommit: CommitRefDto? = null)

@Serializable
data class CommitRefDto(val commitId: String)

@Serializable
data class IterationDto(
    val id: Int,
    val sourceRefCommit: CommitRefDto,
    val targetRefCommit: CommitRefDto,
    val commonRefCommit: CommitRefDto,
    val createdDate: String? = null,
    /** Flags enum rendered as text, e.g. "push" or "forcePush". */
    val reason: String? = null,
)

/** A commit as listed for an iteration. */
@Serializable
data class GitCommitRefDto(val commitId: String, val comment: String? = null, val author: GitUserDateDto? = null)

@Serializable
data class GitUserDateDto(val name: String? = null, val date: String? = null)

/**
 * `path` is null for deleted files; their path is in [ChangeEntryDto.originalPath].
 * `objectId` is the blob in the iteration, `originalObjectId` the blob before it (the only one for deletes).
 */
@Serializable
data class ChangeItemDto(val path: String? = null, val objectId: String? = null, val originalObjectId: String? = null)

@Serializable
data class ChangeEntryDto(
    val changeTrackingId: Int,
    /** Flags enum rendered as text, e.g. "edit" or "edit, rename". */
    val changeType: String,
    val item: ChangeItemDto = ChangeItemDto(),
    val originalPath: String? = null,
)

/** Both next* values are 0 when there are no more pages. */
@Serializable
data class IterationChangesDto(val changeEntries: List<ChangeEntryDto>, val nextSkip: Int = 0, val nextTop: Int = 0)

@Serializable
data class FilePositionDto(val line: Int, val offset: Int)

@Serializable
data class ThreadContextDto(
    val filePath: String,
    val leftFileStart: FilePositionDto? = null,
    val leftFileEnd: FilePositionDto? = null,
    val rightFileStart: FilePositionDto? = null,
    val rightFileEnd: FilePositionDto? = null,
)

@Serializable
data class IterationContextDto(val firstComparingIteration: Int, val secondComparingIteration: Int)

@Serializable
data class PullRequestThreadContextDto(val changeTrackingId: Int? = null, val iterationContext: IterationContextDto? = null)

@Serializable
data class CommentDto(
    val id: Int,
    val parentCommentId: Int = 0,
    val author: IdentityRefDto,
    val content: String? = null,
    val publishedDate: String,
    val lastContentUpdatedDate: String? = null,
    val commentType: String? = null,
    val isDeleted: Boolean = false,
)

@Serializable
data class ThreadDto(
    val id: Int,
    /** Absent on system threads. */
    val status: String? = null,
    val threadContext: ThreadContextDto? = null,
    val pullRequestThreadContext: PullRequestThreadContextDto? = null,
    val comments: List<CommentDto> = emptyList(),
    val isDeleted: Boolean = false,
)

@Serializable
data class NewCommentDto(val content: String, val parentCommentId: Int = 0, val commentType: String = "text")

@Serializable
data class NewThreadDto(
    val comments: List<NewCommentDto>,
    val status: String,
    val threadContext: ThreadContextDto? = null,
    val pullRequestThreadContext: PullRequestThreadContextDto? = null,
)

@Serializable
data class ThreadStatusUpdateDto(val status: String)

@Serializable
data class ReviewerVoteDto(val vote: Int, val isRequired: Boolean)

@Serializable
data class NewReviewerDto(val vote: Int = 0, val isRequired: Boolean = false)

@Serializable
data class CommentUpdateDto(val content: String)

@Serializable
data class SubjectQueryDto(val query: String, val subjectKind: List<String> = listOf("User"))

/** The subject query answers `"value": null` when nothing matches. */
@Serializable
data class SubjectQueryResultDto(val value: List<GraphSubjectDto>? = null)

@Serializable
data class GraphSubjectDto(
    val descriptor: String,
    val displayName: String? = null,
    val mailAddress: String? = null,
    val principalName: String? = null,
)

@Serializable
data class TeamDto(val id: String, val name: String? = null)

@Serializable
data class TeamMemberDto(val identity: IdentityRefDto)

@Serializable
data class StorageKeyDto(val value: String)

@Serializable
data class IdentityDto(val id: String, val providerDisplayName: String? = null, val customDisplayName: String? = null)

@Serializable
data class ConnectionDataDto(val authenticatedUser: IdentityDto)
