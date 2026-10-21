package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The pull request's changed files as folders and files, single-child folders merged ("src/Orders"). A blue dot at the
 * right edge marks a file not viewed yet; clicking the dot toggles the mark. Double-click or Enter calls [onOpen].
 */
class ReviewFilesTree(
    private val vm: PullRequestDetailsViewModel,
    scope: CoroutineScope,
    private val onOpen: (FileChange) -> Unit,
) : Tree(DefaultTreeModel(DefaultMutableTreeNode())) {
    /** A folder; [files] counts the files below it. */
    private data class Folder(val name: String, val files: Int)

    private var comments: Map<String, Int> = emptyMap()

    init {
        isRootVisible = false
        // Setters, not assignments: JTree has protected fields with these names, and setting those skips the UI update.
        setShowsRootHandles(true)
        setCellRenderer(Renderer())
        emptyText.text = "Loading changes…"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val change = changeAt(e) ?: return
                when {
                    e.clickCount == 1 && isOnDot(e) -> vm.setViewed(change, !vm.isViewed(change))
                    e.clickCount == 2 -> onOpen(change)
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val onDot = changeAt(e) != null && isOnDot(e)
                cursor = if (onDot) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                toolTipText = if (onDot) "Toggle viewed" else null
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val change = selectedChange() ?: return
                if (e.keyCode == KeyEvent.VK_ENTER) onOpen(change).also { e.consume() }
            }
        })
        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
        scope.launch(Dispatchers.EDT) { vm.viewed.collect { repaint() } }
        scope.launch(Dispatchers.EDT) {
            vm.threads.collect { threads ->
                comments = threads.filter { !it.isSystem }.mapNotNull { it.anchor?.path }.groupingBy { it }.eachCount()
                repaint()
            }
        }
    }

    fun selectedChange(): FileChange? = (selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileChange

    fun select(change: FileChange) {
        val node = TreeUtil.treeNodeTraverser(model.root as DefaultMutableTreeNode)
            .filter(DefaultMutableTreeNode::class.java).firstOrNull { it.userObject == change } ?: return
        TreeUtil.selectPath(this, TreePath(node.path))
    }

    private fun render(state: State) {
        setPaintBusy(state is State.Loading)
        when (state) {
            State.Loading -> emptyText.text = "Loading changes…"
            is State.Failed -> emptyText.setText(state.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
            is State.Loaded -> {
                (model as DefaultTreeModel).setRoot(buildTree(state.changes))
                TreeUtil.expandAll(this)
                vm.shownFile.value?.let(::select)
                emptyText.text = "No changed files"
            }
        }
    }

    private fun buildTree(changes: List<FileChange>): DefaultMutableTreeNode {
        class Dir(val name: String) {
            val dirs = sortedMapOf<String, Dir>()
            val files = mutableListOf<FileChange>()
            fun count(): Int = files.size + dirs.values.sumOf { it.count() }
        }
        val root = Dir("")
        changes.forEach { change ->
            val parts = change.path.trim('/').split('/')
            var dir = root
            parts.dropLast(1).forEach { dir = dir.dirs.getOrPut(it) { Dir(it) } }
            dir.files += change
        }
        fun toNode(dir: Dir, name: String): DefaultMutableTreeNode {
            // Merge a chain of folders that only contain one folder each.
            var current = dir
            var label = name
            while (current.files.isEmpty() && current.dirs.size == 1) {
                val child = current.dirs.values.single()
                label = if (label.isEmpty()) child.name else "$label/${child.name}"
                current = child
            }
            val node = DefaultMutableTreeNode(Folder(label, current.count()))
            current.dirs.values.forEach { node.add(toNode(it, it.name)) }
            current.files.sortedBy { it.path }.forEach { node.add(DefaultMutableTreeNode(it)) }
            return node
        }
        val top = DefaultMutableTreeNode()
        root.dirs.values.forEach { top.add(toNode(it, it.name)) }
        root.files.sortedBy { it.path }.forEach { top.add(DefaultMutableTreeNode(it)) }
        return top
    }

    private fun changeAt(e: MouseEvent): FileChange? =
        (getClosestPathForLocation(e.x, e.y)?.takeIf { getPathBounds(it)?.let { b -> e.y in b.y until b.y + b.height } == true }
            ?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileChange

    private fun isOnDot(e: MouseEvent) = e.x >= visibleRect.x + visibleRect.width - JBUI.scale(28)

    /** Draws the unviewed dots at the right edge of the visible area, whatever the row's width. */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = DOT
            val d = JBUI.scale(7)
            val x = visibleRect.x + visibleRect.width - JBUI.scale(16)
            val viewed = vm.viewed.value
            for (row in 0 until rowCount) {
                val bounds = getRowBounds(row) ?: continue
                if (bounds.y > visibleRect.y + visibleRect.height) break
                val change = (getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileChange ?: continue
                if (!vm.isViewed(change, viewed)) g2.fillOval(x, bounds.y + (bounds.height - d) / 2, d, d)
            }
        } finally {
            g2.dispose()
        }
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Folder -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.name)
                    append("  ${item.files} ${if (item.files == 1) "file" else "files"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FileChange -> {
                    val name = item.path.substringAfterLast('/')
                    val status = when (item.kind) {
                        ChangeKind.ADDED -> FileStatus.ADDED
                        ChangeKind.DELETED -> FileStatus.DELETED
                        ChangeKind.MODIFIED, ChangeKind.RENAMED -> FileStatus.MODIFIED
                    }
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    append(name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                    if (item.kind == ChangeKind.RENAMED) append("  ← ${item.oldPath?.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    comments[item.path]?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    toolTipText = item.path
                }
            }
        }
    }

    private companion object {
        val DOT = JBColor(0x3574F0, 0x548AF7)
    }
}
