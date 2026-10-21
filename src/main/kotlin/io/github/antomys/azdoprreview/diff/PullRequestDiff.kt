package io.github.antomys.azdoprreview.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.Companion.diffSides
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.DiffSide

object PullRequestDiff {
    /** Opens the IDE diff for [changes], starting at [selected]; each file loads when it is shown. */
    fun show(project: Project, target: ReviewTarget, revision: Revision, changes: List<FileChange>, selected: Int) {
        val producers = changes.map { FileDiffProducer(project, target, revision, it) }
        DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducers(producers, selected), DiffDialogHints.DEFAULT)
    }
}

private class FileDiffProducer(
    private val project: Project,
    private val target: ReviewTarget,
    private val revision: Revision,
    private val change: FileChange,
) : DiffRequestProducer {
    override fun getName(): String = change.newPath ?: change.oldPath.orEmpty()

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val (left, right) = diffSides(change, revision)
        return SimpleDiffRequest(name, content(left), content(right), title(left, "base"), title(right, "iteration ${revision.id}"))
    }

    private fun content(side: DiffSide?): DiffContent {
        val factory = DiffContentFactory.getInstance()
        if (side == null) return factory.createEmpty()
        val bytes = try {
            runBlockingCancellable { target.provider.fileContent(target.repo, side.path, side.commit) }
        } catch (e: AzureDevOpsException) {
            throw DiffRequestProducerException(e.message ?: "Can't load ${side.path}")
        } ?: return factory.createEmpty()

        val fileName = side.path.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        return when {
            0.toByte() in bytes -> factory.createBinary(project, bytes, fileType, fileName)
            fileType is UnknownFileType -> factory.create(project, bytes.decodeToString(), PlainTextFileType.INSTANCE)
            else -> factory.create(project, bytes.decodeToString(), fileType)
        }
    }

    private fun title(side: DiffSide?, label: String) = side?.let { "${it.path} ($label, ${it.commit.take(8)})" }
}
