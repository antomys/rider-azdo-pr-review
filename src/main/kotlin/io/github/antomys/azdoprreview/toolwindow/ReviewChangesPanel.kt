package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The changed files as a directory tree; each file's checkbox is its "viewed" mark.
 * Clicking a file shows it in the review's diff; the selection follows the file the diff shows.
 */
class ReviewChangesPanel(
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    private val diff: ReviewDiff,
) : SimpleToolWindowPanel(true, true) {
    private val root = CheckedTreeNode("root")
    private val renderer = Renderer()

    /** A folder's checkbox sets all its files; it shows as checked only when every file is. */
    private val tree = object : CheckboxTree(renderer, root, CheckboxTreeBase.CheckPolicy(true, true, false, true)) {
        // The checkbox tree consumes double-clicks itself and reports them here.
        override fun onDoubleClick(node: CheckedTreeNode?) {
            openDiff(node?.userObject as? FileChange, focus = true)
        }
    }
    private val title = JBLabel().apply { border = JBUI.Borders.empty(0, 8) }

    /** Comment count per file path, for the renderer. */
    private var comments: Map<String, Int> = emptyMap()

    /** True while checkboxes are updated from the view model, so the listener doesn't write back. */
    private var syncing = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "Loading changes…"
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (!syncing) saveViewedMarks()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openDiff(selectedChange(), focus = true)) e.consume()
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                // A click on the checkbox only changes the viewed mark.
                if (e.x - bounds.x <= renderer.threeStateCheckBox.preferredSize.width) return
                openDiff((path.lastPathComponent as? CheckedTreeNode)?.userObject as? FileChange, focus = false)
            }
        })

        val expander = DefaultTreeExpander(tree)
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Next Unviewed File", "Open the next file you haven't viewed", AllIcons.Actions.Forward) {
                override fun actionPerformed(e: AnActionEvent) {
                    vm.nextUnviewed(selectedChange())?.let { select(it); openDiff(it, focus = false) }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = vm.nextUnviewed(null) != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
            CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrChanges", actions, true)
        toolbar.targetComponent = tree
        setToolbar(JPanel(BorderLayout()).apply {
            add(title, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.EAST)
        })
        setContent(ScrollPaneFactory.createScrollPane(tree, true))

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
        scope.launch(Dispatchers.EDT) { vm.viewed.collect { syncViewedMarks() } }
        scope.launch(Dispatchers.EDT) { vm.shownFile.collect { shown -> if (shown != null && shown != selectedChange()) select(shown) } }
        scope.launch(Dispatchers.EDT) {
            vm.threads.collect { threads ->
                comments = threads.filter { !it.isSystem }.mapNotNull { it.anchor?.path }.groupingBy { it }.eachCount()
                tree.repaint()
            }
        }
    }

    private fun render(state: State) {
        tree.setPaintBusy(state is State.Loading)
        when (state) {
            State.Loading -> tree.emptyText.text = "Loading changes…"
            is State.Failed -> tree.emptyText.setText(state.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
            is State.Loaded -> {
                root.removeAllChildren()
                state.changes.groupBy { it.path.substringBeforeLast('/', "") }.forEach { (dir, files) ->
                    val parent = if (dir.isEmpty()) root else CheckedTreeNode(dir.removePrefix("/")).also(root::add)
                    files.forEach { parent.add(CheckedTreeNode(it)) }
                }
                (tree.model as DefaultTreeModel).reload()
                TreeUtil.expandAll(tree)
                tree.emptyText.text = "No changed files"
                syncViewedMarks()
            }
        }
    }

    private fun fileNodes(): List<CheckedTreeNode> =
        TreeUtil.treeNodeTraverser(root).filter(CheckedTreeNode::class.java).filter { it.userObject is FileChange }.toList()

    private fun syncViewedMarks() {
        syncing = true
        try {
            val viewed = vm.viewed.value
            fileNodes().forEach { tree.setNodeState(it, vm.isViewed(it.userObject as FileChange, viewed)) }
        } finally {
            syncing = false
        }
        val files = fileNodes()
        title.text = if (files.isEmpty()) "Changes" else "Changes · ${files.count { it.isChecked }} of ${files.size} viewed"
        tree.repaint()
    }

    /** A directory checkbox changes its files too, so store every file's state. */
    private fun saveViewedMarks() {
        fileNodes().forEach { node ->
            val change = node.userObject as FileChange
            if (node.isChecked != vm.isViewed(change)) vm.setViewed(change, node.isChecked)
        }
    }

    private fun selectedChange(): FileChange? = (tree.selectionPath?.lastPathComponent as? CheckedTreeNode)?.userObject as? FileChange

    private fun select(change: FileChange) {
        val node = fileNodes().firstOrNull { it.userObject == change } ?: return
        TreeUtil.selectPath(tree, TreePath(node.path))
    }

    private fun openDiff(change: FileChange?, focus: Boolean): Boolean {
        if (change == null) return false
        diff.show(change, focus)
        return true
    }

    private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? CheckedTreeNode ?: return
            val text = textRenderer
            when (val item = node.userObject) {
                is FileChange -> {
                    val name = item.path.substringAfterLast('/')
                    val status = when (item.kind) {
                        ChangeKind.ADDED -> FileStatus.ADDED
                        ChangeKind.DELETED -> FileStatus.DELETED
                        ChangeKind.MODIFIED, ChangeKind.RENAMED -> FileStatus.MODIFIED
                    }
                    text.icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    text.append(name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                    if (item.kind == ChangeKind.RENAMED) text.append("  ← ${item.oldPath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    comments[item.path]?.let { text.append("  $it ${if (it == 1) "comment" else "comments"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
                is String -> {
                    text.icon = AllIcons.Nodes.Folder
                    text.append(item)
                }
            }
        }
    }
}
