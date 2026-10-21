package io.github.antomys.azdoprreview.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import io.github.antomys.azdoprreview.azdo.AzureDevOpsException
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.Companion.diffSides
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.DiffSide
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.CoroutineScope

/** The pull request file a diff shows; [ReviewDiffExtension] uses it to add the file's threads to the viewer. */
data class ReviewDiffFile(val project: Project, val vm: PullRequestDetailsViewModel, val scope: CoroutineScope, val change: FileChange)

object PullRequestDiff {
    val FILE: Key<ReviewDiffFile> = Key.create("AzureDevOpsPrReview.diffFile")
}

/**
 * The pull request's diff, in one editor tab: showing another file switches that tab to it.
 * Each file loads, and is marked viewed, when shown.
 */
class ReviewDiff(private val project: Project, private val vm: PullRequestDetailsViewModel, private val scope: CoroutineScope) {
    private var file: ReviewDiffVirtualFile? = null
    private val toolbar = ReviewDiffActions(project, vm, scope, this).actions

    /** Whether the diff tab is open. */
    fun isOpen(): Boolean = file?.let(FileEditorManager.getInstance(project)::isFileOpen) == true

    fun show(change: FileChange, focus: Boolean) {
        val state = vm.state.value as? State.Loaded ?: return
        val index = state.changes.indexOf(change).takeIf { it >= 0 } ?: return
        val tabs = DiffEditorTabFilesManager.getInstance(project)
        val editors = FileEditorManager.getInstance(project)
        val current = file
        val processor = current?.processor
        if (current != null && current.state == state && processor != null && editors.isFileOpen(current)) {
            processor.setCurrentRequest(index)
            tabs.showDiffFile(current, focus)
            return
        }
        // A refresh can change the file list, so a stale tab is replaced rather than reused.
        current?.let(editors::closeFile)
        val producers = state.changes.map { FileDiffProducer(project, ReviewDiffFile(project, vm, scope, it), state, toolbar) }
        val name = "PR #${vm.summary.ref.id}: ${vm.summary.title}"
        file = ReviewDiffVirtualFile(SimpleDiffRequestChain.fromProducers(producers, index), name, state).also { tabs.showDiffFile(it, focus) }
    }
}

/** Keeps the processor it creates, so the open tab can be switched to another file. */
private class ReviewDiffVirtualFile(chain: DiffRequestChain, name: String, val state: State.Loaded) : ChainDiffVirtualFile(chain, name) {
    var processor: CacheDiffRequestChainProcessor? = null
        private set

    override fun createProcessor(project: Project): DiffRequestProcessor =
        CacheDiffRequestChainProcessor(project, chain).also { processor = it }
}

private class FileDiffProducer(
    private val project: Project,
    private val file: ReviewDiffFile,
    private val state: State.Loaded,
    private val toolbar: List<AnAction>,
) : DiffRequestProducer {
    private val change = file.change
    private val target = file.vm.target

    override fun getName(): String = change.newPath ?: change.oldPath.orEmpty()

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val (left, right) = diffSides(change, state.revision)
        val request = SimpleDiffRequest(name, content(left), content(right), title(left, "base"), title(right, "iteration ${state.revision.id}"))
        request.putUserData(PullRequestDiff.FILE, file)
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, toolbar)
        return request
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
