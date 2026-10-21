package io.github.antomys.azdoprreview.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.settings.AzureDevOpsConfigurable
import io.github.antomys.azdoprreview.settings.AzureDevOpsConnection
import io.github.antomys.azdoprreview.settings.ViewedFilesStore
import io.github.antomys.azdoprreview.timeline.TimelineFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import javax.swing.JComponent

class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val connection = project.service<AzureDevOpsConnection>()
        val scope = connection.scope.childScope()
        val vm = PullRequestListViewModel(scope, connection::target)
        val panel = PullRequestListPanel(
            vm, scope,
            openSettings = {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AzureDevOpsConfigurable::class.java)
                vm.refresh()
            },
            openReview = { review -> vm.target?.let { openDetails(project, toolWindow, scope, it, review) } },
        )
        addContent(toolWindow, panel, "Pull Requests", scope, closeable = false)
        vm.refresh()
    }

    /** Shows the review's details tab and its timeline editor, reusing them if they're already open. */
    private fun openDetails(project: Project, toolWindow: ToolWindow, parent: CoroutineScope, target: ReviewTarget, review: ReviewSummary) {
        val contents = toolWindow.contentManager
        contents.contents.firstOrNull { it.getUserData(REVIEW) == review.ref }?.let {
            contents.setSelectedContent(it)
            it.getUserData(TIMELINE)?.let { timeline -> FileEditorManager.getInstance(project).openFile(timeline, true) }
            return
        }
        val scope = parent.childScope()
        val vm = PullRequestDetailsViewModel(scope, target, review, ViewedFilesStore.getInstance())
        val diff = ReviewDiff(project, vm, scope)
        val timeline = TimelineFile(project, vm, scope, diff)
        val openTimeline = { FileEditorManager.getInstance(project).openFile(timeline, true); Unit }
        val list = contents.getContent(0)!!
        val panel = PullRequestDetailsPanel(project, vm, scope, diff, onBack = { contents.setSelectedContent(list) }, openTimeline = openTimeline)
        addContent(toolWindow, panel, "#${review.ref.id}", scope, closeable = true).apply {
            putUserData(REVIEW, review.ref)
            putUserData(TIMELINE, timeline)
        }
        // The timeline shows this tab's data, so it closes with the tab.
        scope.coroutineContext.job.invokeOnCompletion {
            ApplicationManager.getApplication().invokeLater({ FileEditorManager.getInstance(project).closeFile(timeline) }, project.disposed)
        }
        vm.refresh()
        openTimeline()
    }

    /** Adds a tab whose [scope] is cancelled when the tab closes. */
    private fun addContent(toolWindow: ToolWindow, component: JComponent, name: String, scope: CoroutineScope, closeable: Boolean) =
        ContentFactory.getInstance().createContent(component, name, false).also {
            it.isCloseable = closeable
            Disposer.register(it) { scope.cancel() }
            toolWindow.contentManager.addContent(it)
            toolWindow.contentManager.setSelectedContent(it)
        }

    private fun CoroutineScope.childScope() = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))

    private companion object {
        val REVIEW = Key.create<ReviewRef>("AzureDevOpsPrReview.review")
        val TIMELINE = Key.create<TimelineFile>("AzureDevOpsPrReview.timeline")
    }
}
