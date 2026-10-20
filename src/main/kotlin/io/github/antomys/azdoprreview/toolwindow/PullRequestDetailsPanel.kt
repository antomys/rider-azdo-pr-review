package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.diff.PullRequestDiff
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class PullRequestDetailsPanel(
    private val project: Project,
    private val vm: PullRequestDetailsViewModel,
    scope: CoroutineScope,
) : SimpleToolWindowPanel(true, true) {
    private val title = JBLabel().apply { font = JBUI.Fonts.label().biggerOn(2f).asBold() }
    private val branches = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val reviewers = JBLabel()
    private val description = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private val model = CollectionListModel<FileChange>()
    private val files = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ChangeRenderer()
    }

    @Volatile
    private var loaded: State.Loaded? = null

    init {
        val summary = vm.summary
        title.text = "#${summary.ref.id} ${summary.title}"
        branches.text = "${summary.sourceBranch} → ${summary.targetBranch} · by ${summary.author.displayName}"

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            listOf(title, branches, reviewers, ActionLink("Open in browser") { BrowserUtil.browse(vm.webUrl()) }, description)
                .forEach { add(it.apply { alignmentX = LEFT_ALIGNMENT }) }
        }
        val refresh = object : DumbAwareAction("Refresh", "Reload the pull request", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                vm.refresh()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrDetails", DefaultActionGroup(refresh), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(
            OnePixelSplitter(true, 0.35f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(header, true)
                secondComponent = ScrollPaneFactory.createScrollPane(files, true)
            },
        )

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openDiff()
        }.installOn(files)
        files.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openDiff()) e.consume()
            }
        })

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
    }

    private fun openDiff(): Boolean {
        val state = loaded ?: return false
        val index = files.selectedIndex.takeIf { it >= 0 } ?: return false
        PullRequestDiff.show(project, vm.target, state.revision, state.changes, index)
        return true
    }

    private fun render(state: State) {
        loaded = state as? State.Loaded
        files.setPaintBusy(state is State.Loading)
        val empty = files.emptyText.clear()
        when (state) {
            State.Loading -> {
                model.removeAll()
                empty.appendText("Loading changes…")
            }
            is State.Failed -> {
                model.removeAll()
                empty.appendText(state.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
                    .appendSecondaryText("Retry", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { vm.refresh() }
            }
            is State.Loaded -> {
                description.text = state.details.description.ifBlank { "No description" }
                reviewers.text = if (state.details.reviewers.isEmpty()) "No reviewers" else
                    "Reviewers: " + state.details.reviewers.joinToString { r ->
                        r.user.displayName + (if (r.isRequired) " (required)" else "") + voteLabel(r.vote)
                    }
                model.replaceAll(state.changes)
                empty.appendText("No changed files")
            }
        }
    }

    private class ChangeRenderer : ColoredListCellRenderer<FileChange>() {
        override fun customizeCellRenderer(list: JList<out FileChange>, value: FileChange, index: Int, selected: Boolean, hasFocus: Boolean) {
            val status = when (value.kind) {
                ChangeKind.ADDED -> FileStatus.ADDED
                ChangeKind.DELETED -> FileStatus.DELETED
                ChangeKind.MODIFIED, ChangeKind.RENAMED -> FileStatus.MODIFIED
            }
            val path = value.newPath ?: value.oldPath.orEmpty()
            append(path.substringAfterLast('/'), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
            val dir = path.substringBeforeLast('/', "").ifEmpty { "/" }
            val renamed = if (value.kind == ChangeKind.RENAMED) "  renamed from ${value.oldPath}" else ""
            append("  $dir$renamed", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        fun voteLabel(vote: Vote) = when (vote) {
            Vote.APPROVED -> " ✓ approved"
            Vote.APPROVED_WITH_SUGGESTIONS -> " ✓ approved with suggestions"
            Vote.WAITING_FOR_AUTHOR -> " ⏳ waiting for author"
            Vote.REJECTED -> " ✗ rejected"
            Vote.NONE -> ""
        }
    }
}
