package io.github.antomys.azdoprreview.timeline

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import kotlinx.coroutines.CoroutineScope
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** The editor tab "Timeline for #id": the pull request's description and discussion in order. */
class TimelineFile(
    val project: Project,
    val vm: PullRequestDetailsViewModel,
    val scope: CoroutineScope,
    val diff: ReviewDiff,
) : LightVirtualFile("Timeline for #${vm.summary.ref.id}") {
    init {
        isWritable = false
    }
}

class TimelineEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is TimelineFile

    override fun acceptRequiresReadAction() = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TimelineEditor(file as TimelineFile)

    override fun getEditorTypeId() = "AzureDevOpsPrTimeline"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class TimelineEditor(private val file: TimelineFile) : UserDataHolderBase(), FileEditor {
    private val panel = TimelinePanel(file.project, file.vm, file.scope, file.diff)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName() = "Timeline"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}
