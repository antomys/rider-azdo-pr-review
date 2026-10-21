package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.toolwindow.DraftComment
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.toolwindow.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The AI review settings a run uses. */
data class AiReviewOptions(
    val excludes: List<String> = AiReviewRequests.DEFAULT_EXCLUDES,
    val instructions: String = "",
    val maxFindings: Int = AiReviewRequests.DEFAULT_MAX_FINDINGS,
    val maxBytes: Int = AiReviewRequests.DEFAULT_MAX_BYTES,
)

/**
 * One AI review of a loaded pull request, without Swing: [prepare] builds what would be sent, [review] sends what the
 * user agreed to and keeps the findings that fit the diff, and [addDrafts] or [postNow] turn the chosen ones into comments.
 */
class AiReviewSession(private val pr: PullRequestDetailsViewModel, val reviewer: AiReviewer, private val options: AiReviewOptions) {
    /** The request with every file that can be sent, and the changed files that won't be, with the reason. */
    data class Prepared(val request: AiReviewRequest, val excluded: List<ExcludedFile>)

    /** Fetches both sides of every changed file not matching an exclude glob and diffs them. */
    suspend fun prepare(): Prepared {
        val loaded = pr.state.value as? State.Loaded ?: throw AiReviewException("The pull request isn't loaded")
        val (excludedChanges, included) = loaded.changes.partition { change ->
            listOfNotNull(change.newPath, change.oldPath).any { AiReviewRequests.matchingGlob(it, options.excludes) != null }
        }
        val byGlob = excludedChanges.map { change ->
            val glob = listOfNotNull(change.newPath, change.oldPath).firstNotNullOf { AiReviewRequests.matchingGlob(it, options.excludes) }
            ExcludedFile(change.path, "matches $glob")
        }
        val provider = pr.target.provider
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        val contents = coroutineScope {
            included.map { change ->
                async {
                    permits.withPermit {
                        val (old, new) = PullRequestDetailsViewModel.diffSides(change, loaded.revision)
                        FileContents(
                            change,
                            old?.let { provider.fileContent(pr.target.repo, it.path, it.commit) },
                            new?.let { provider.fileContent(pr.target.repo, it.path, it.commit) },
                        )
                    }
                }
            }.awaitAll()
        }
        val (files, notDiffed) = withContext(Dispatchers.Default) { AiReviewRequests.diffFiles(contents, options.maxBytes) }
        val details = loaded.details
        return Prepared(
            AiReviewRequest(details.summary.title, details.description, files, options.instructions, options.maxFindings),
            byGlob + notDiffed,
        )
    }

    /** Sends [prepared] without the files whose paths are in [leftOut], and returns the findings that fit those diffs. */
    suspend fun review(prepared: Prepared, leftOut: Set<String> = emptySet()): ValidatedFindings {
        val request = prepared.request.copy(files = prepared.request.files.filterNot { it.change.path in leftOut })
        if (request.files.isEmpty()) throw AiReviewException("No files to send")
        val result = reviewer.review(request)
        return withContext(Dispatchers.Default) {
            AiReviewRequests.validate(result.findings, request.files, request.maxFindings).let { it.copy(outsideDiff = it.outsideDiff + result.skipped) }
        }
    }

    /** Each finding becomes a pending draft on its file and lines, sent later with Submit Review. */
    fun addDrafts(findings: List<ReviewFinding>): List<DraftComment> = findings.map { pr.addDraft(it.anchor, null, it.comment) }

    /** Posts each finding as an active thread now, in order; a failure reports how many were posted before it. */
    suspend fun postNow(findings: List<ReviewFinding>) {
        findings.forEachIndexed { index, finding ->
            try {
                pr.createThread(finding.anchor, finding.comment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AiReviewException("Posted $index of ${findings.size} findings, then failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val PARALLEL_DOWNLOADS = 4
    }
}
