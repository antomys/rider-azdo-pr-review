package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.Side

/**
 * A local tool that reviews a pull request's diffs and returns findings. It never posts anything: the user previews
 * the findings and picks what to post.
 */
interface AiReviewer {
    /** E.g. "claude-code", "fake". */
    val id: String

    /** Shown to the user, e.g. "Claude Code". */
    val name: String

    /** Whether the tool can run, checked locally without a network call. It may start a process: call off the EDT. */
    fun availability(): Availability

    /** The command line shown in the consent dialog, without the prompt. */
    fun commandLine(): String

    /** Reviews [request]. Cancelling the calling coroutine stops the tool. Throws [AiReviewException] on failure. */
    suspend fun review(request: AiReviewRequest): AiReviewResult
}

sealed interface Availability {
    /** [version] as the tool reports it; [location] is where it was found. */
    data class Available(val version: String, val location: String) : Availability

    data class Unavailable(val reason: String) : Availability
}

enum class Severity { INFO, WARNING, ERROR }

/**
 * One changed file as sent to the tool: [diff] is a unified diff of the merge base vs. the latest iteration.
 * [oldLines] and [newLines] count the file's lines on each side (0 where the file doesn't exist), for validating findings.
 */
data class FileDiff(val change: FileChange, val diff: String, val oldLines: Int, val newLines: Int) {
    val bytes: Int get() = diff.toByteArray().size
}

data class AiReviewRequest(
    val title: String,
    val description: String,
    val files: List<FileDiff>,
    val instructions: String,
    val maxFindings: Int,
)

/** A finding as the tool returned it: untrusted, and not yet checked against the diff. Lines are 1-based. */
data class AiFinding(
    val path: String,
    val side: Side,
    val startLine: Int,
    val endLine: Int,
    val severity: Severity,
    val title: String,
    val body: String,
)

/** [skipped] counts returned findings too malformed to read (no path or line). */
data class AiReviewResult(val findings: List<AiFinding>, val skipped: Int = 0, val rawLog: String? = null)

/** A failed review; [details] is the tool's output tail for the error message, never containing secrets. */
open class AiReviewException(message: String, val details: String? = null) : Exception(message)
