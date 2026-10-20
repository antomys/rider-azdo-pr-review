package io.github.antomys.azdoprreview.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.antomys.azdoprreview.settings.AzureDevOpsConfigurable
import io.github.antomys.azdoprreview.settings.AzureDevOpsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val connection = project.service<AzureDevOpsConnection>()
        // Child of the project scope, cancelled when the tool window content goes away.
        val scope = CoroutineScope(connection.scope.coroutineContext + SupervisorJob(connection.scope.coroutineContext.job))
        val vm = PullRequestListViewModel(scope, connection::target)
        val panel = PullRequestListPanel(vm, scope) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AzureDevOpsConfigurable::class.java)
            vm.refresh()
        }
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        Disposer.register(content) { scope.cancel() }
        toolWindow.contentManager.addContent(content)
        vm.refresh()
    }
}
