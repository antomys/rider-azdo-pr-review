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
)

@Serializable
data class CommitRefDto(val commitId: String)

@Serializable
data class IterationDto(
    val id: Int,
    val sourceRefCommit: CommitRefDto,
    val targetRefCommit: CommitRefDto,
    val commonRefCommit: CommitRefDto,
)

/** `path` is null for deleted files; their path is in [ChangeEntryDto.originalPath]. */
@Serializable
data class ChangeItemDto(val path: String? = null)

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
data class CommentUpdateDto(val content: String)

@Serializable
data class SubjectQueryDto(val query: String, val subjectKind: List<String> = listOf("User"))

@Serializable
data class GraphSubjectDto(
    val descriptor: String,
    val displayName: String? = null,
    val mailAddress: String? = null,
    val principalName: String? = null,
)

@Serializable
data class StorageKeyDto(val value: String)

@Serializable
data class IdentityDto(val id: String, val providerDisplayName: String? = null, val customDisplayName: String? = null)

@Serializable
data class ConnectionDataDto(val authenticatedUser: IdentityDto)
