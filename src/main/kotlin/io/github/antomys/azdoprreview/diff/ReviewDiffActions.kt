package io.github.antomys.azdoprreview.diff

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.toolwindow.ReviewFilesTree
import io.github.antomys.azdoprreview.toolwindow.SubmitReviewPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The review tools in the diff toolbar: "← 1/3 files →", Submit Review, and previous / next comment. */
class ReviewDiffActions(private val project: Project, private val vm: PullRequestDetailsViewModel, private val scope: CoroutineScope, private val diff: ReviewDiff) {
    val actions: List<AnAction> = listOf(
        FileAction(forward = false),
        FilesPopupAction(),
        FileAction(forward = true),
        SubmitAction(),
        CommentAction(forward = false),
        CommentAction(forward = true),
    )

    private fun changes() = (vm.state.value as? State.Loaded)?.changes.orEmpty()

    private fun shownIndex() = changes().indexOf(vm.shownFile.value)

    private inner class FileAction(private val forward: Boolean) : DumbAwareAction(
        if (forward) "Next File" else "Previous File", null, if (forward) AllIcons.Actions.Forward else AllIcons.Actions.Back,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            changes().getOrNull(shownIndex() + if (forward) 1 else -1)?.let { diff.show(it, focus = true) }
        }

        override fun update(e: AnActionEvent) {
            val index = shownIndex()
            e.presentation.isEnabled = if (forward) index in 0 until changes().size - 1 else index > 0
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** "1/3 files"; opens the files tree, where a file opens and its viewed mark can be toggled. */
    private inner class FilesPopupAction : DumbAwareAction("Files"), CustomComponentAction {
        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun update(e: AnActionEvent) {
            e.presentation.text = "${shownIndex() + 1}/${changes().size} files"
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            ActionLink(presentation.text) { event -> showFiles(event.source as JComponent) }.apply { border = JBUI.Borders.empty(0, 4) }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            (component as ActionLink).text = presentation.text
        }
    }

    private fun showFiles(anchor: JComponent) {
        val popupScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        var popup: JBPopup? = null
        val tree = ReviewFilesTree(vm, popupScope) { change: FileChange ->
            diff.show(change, focus = true)
            popup?.cancel()
        }
        vm.shownFile.value?.let(tree::select)
        val expand = object : DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
        }
        val collapse = object : DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 0)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrFiles", DefaultActionGroup(expand, collapse), true)
        toolbar.targetComponent = tree
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(360))
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, tree)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = popupScope.cancel()
            })
            .createPopup()
            .also { it.showUnderneathOf(anchor) }
    }

    private inner class SubmitAction : DumbAwareAction("Submit Review…"), CustomComponentAction {
        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun update(e: AnActionEvent) {
            val drafts = vm.drafts.value.size
            e.presentation.text = if (drafts > 0) "Submit Review ($drafts)…" else "Submit Review…"
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            JButton(presentation.text).apply {
                putClientProperty("JButton.buttonType", "default")
                addActionListener { SubmitReviewPopup.show(project, vm, scope, this) }
            }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            (component as JButton).text = presentation.text
        }
    }

    private inner class CommentAction(private val forward: Boolean) : DumbAwareAction(
        if (forward) "Next Comment" else "Previous Comment", null,
        if (forward) AllIcons.Actions.NextOccurence else AllIcons.Actions.PreviousOccurence,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val thread = target(e) ?: return
            val change = vm.changeOf(thread) ?: return
            vm.navigateTo(thread)
            diff.show(change, focus = true)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = target(e) != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        /** The thread after or before the caret in the shown file. */
        private fun target(e: AnActionEvent): ReviewThread? {
            val line = e.getData(DiffDataKeys.CURRENT_EDITOR)?.caretModel?.logicalPosition?.line?.plus(1) ?: 0
            return vm.adjacentThread(vm.shownFile.value, line, forward)
        }
    }
}
