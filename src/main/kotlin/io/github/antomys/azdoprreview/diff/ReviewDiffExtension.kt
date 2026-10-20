package io.github.antomys.azdoprreview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.ui.CommentEditor
import io.github.antomys.azdoprreview.ui.Mentions
import io.github.antomys.azdoprreview.ui.ThreadPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import com.intellij.diff.util.Side as EditorSide

/**
 * Shows a pull request file's threads inside its diff, with a gutter "+" to start new ones.
 * Side-by-side diffs and the one-sided view of added or deleted files are supported; the unified view is not yet.
 */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val file = request.getUserData(PullRequestDiff.FILE) ?: return
        // Viewers are created for cached requests too, so this follows every switch between files.
        file.vm.onDiffShown(file.change)
        val editors: (EditorSide) -> EditorEx? = when (viewer) {
            is TwosideTextDiffViewer -> viewer::getEditor
            is OnesideTextDiffViewer -> { side -> viewer.editor.takeIf { side == viewer.side } }
            else -> return
        }
        DiffThreads(viewer, editors, file).install()
    }
}

private class DiffThreads(
    private val viewer: FrameDiffTool.DiffViewer,
    private val editors: (EditorSide) -> EditorEx?,
    private val file: ReviewDiffFile,
) {
    private val change = file.change
    private val threadInlays = mutableListOf<Inlay<*>>()

    /** True once the viewer has computed the diff and done its own initial scrolling. */
    private var ready = false

    fun install() {
        val jobs = listOf(
            file.scope.launch(Dispatchers.EDT) { file.vm.threads.collect(::render) },
            file.scope.launch(Dispatchers.EDT) { file.vm.pendingNavigation.collect { if (ready) scrollToPending() } },
        )
        (viewer as? DiffViewerBase)?.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                if (ready) return
                ready = true
                SwingUtilities.invokeLater(::scrollToPending)
            }
        }) ?: run { ready = true }
        Disposer.register(viewer) {
            jobs.forEach { it.cancel() }
            threadInlays.forEach(Disposer::dispose)
        }
        Side.entries.forEach { side -> editor(side)?.let { installGutter(side, it) } }
    }

    private fun isOnThisFile(thread: ReviewThread) = thread.anchor?.path?.let { it == change.newPath || it == change.oldPath } == true

    private fun sideOf(thread: ReviewThread) = thread.anchor?.lines?.side ?: if (change.newPath != null) Side.RIGHT else Side.LEFT

    /** Scrolls to the thread the user asked to see, if it's on this file. */
    private fun scrollToPending() {
        val thread = file.vm.pendingNavigation.value?.takeIf(::isOnThisFile) ?: return
        val editor = editor(sideOf(thread)) ?: return
        val line = ((thread.anchor?.lines?.start ?: 1) - 1).coerceIn(0, maxOf(editor.document.lineCount - 1, 0))
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        file.vm.navigated(thread)
    }

    private fun path(side: Side) = if (side == Side.LEFT) change.oldPath else change.newPath

    private fun editor(side: Side): EditorEx? =
        path(side)?.let { editors(if (side == Side.LEFT) EditorSide.LEFT else EditorSide.RIGHT) }

    private fun render(threads: List<ReviewThread>) {
        threadInlays.forEach(Disposer::dispose)
        threadInlays.clear()
        threads.filter { !it.isSystem && isOnThisFile(it) }.forEach { thread ->
            val lines = thread.anchor!!.lines
            val editor = editor(sideOf(thread)) ?: return@forEach
            val panel = ThreadPanel(file.project, thread, file.vm, file.scope, UIUtil.getPanelBackground())
            threadInlays += addInlay(editor, panel, lines?.end, above = lines == null)
        }
    }

    /** Adds [component] below 1-based [line], or above the first line when [line] is null. */
    private fun addInlay(editor: EditorEx, component: JComponent, line: Int?, above: Boolean): Inlay<*> {
        val document = editor.document
        val index = ((line ?: 1) - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
        val offset = if (above) 0 else document.getLineEndOffset(index)
        val frame = InlayFrame(component, editor)
        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, above, true, false, 0, offset,
        )
        return EditorEmbeddedComponentManager.getInstance().addComponent(editor, frame, properties)!!.also { frame.inlay = it }
    }

    private fun installGutter(side: Side, editor: EditorEx) {
        var hover: RangeHighlighter? = null
        fun clear() {
            hover?.let(editor.markupModel::removeHighlighter)
            hover = null
        }
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val line = e.logicalPosition.line
                if (line >= editor.document.lineCount) return clear()
                if (hover?.let { editor.document.getLineNumber(it.startOffset) } == line) return
                clear()
                hover = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null).apply {
                    gutterIconRenderer = AddCommentIcon(side, line)
                }
            }
        }, viewer)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(event: EditorMouseEvent) = clear()
        }, viewer)
    }

    /** Opens a new-thread editor below [line] (0-based), covering the selection when it includes that line. */
    private fun startThread(side: Side, line: Int) {
        val editor = editor(side) ?: return
        val path = path(side) ?: return
        val selection = editor.selectionModel
        val document = editor.document
        val (start, end) = if (selection.hasSelection()) {
            val first = document.getLineNumber(selection.selectionStart)
            var last = document.getLineNumber(selection.selectionEnd)
            if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
            if (line in first..last) first to last else line to line
        } else {
            line to line
        }
        var inlay: Inlay<*>? = null
        val draft = CommentEditor(
            file.project, file.scope, "Comment",
            "Comment on ${if (start == end) "line ${end + 1}" else "lines ${start + 1}–${end + 1}"}… (@ to mention)",
            Mentions(file.vm::searchUsers, file.vm::encodeMention),
            submit = { body ->
                file.vm.createThread(ThreadAnchor(path, LineRange(side, start + 1, end + 1)), body)
                inlay?.let(Disposer::dispose)
            },
            cancel = { inlay?.let(Disposer::dispose) },
        )
        val box = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(8, 10))
            add(draft)
        }
        inlay = addInlay(editor, box, end + 1, above = false)
        Disposer.register(viewer, inlay)
        SwingUtilities.invokeLater { draft.focus() }
    }

    private inner class AddCommentIcon(private val side: Side, private val line: Int) : GutterIconRenderer() {
        override fun getIcon() = AllIcons.General.Add
        override fun getTooltipText() = "Add a comment (select lines first to comment on a range)"
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT
        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = startThread(side, line)
        }

        override fun equals(other: Any?) = other is AddCommentIcon && other.side == side && other.line == line
        override fun hashCode() = 31 * side.hashCode() + line
    }
}

/**
 * Sizes an embedded component to a readable width within the editor, and updates the inlay when its height changes
 * (wrapped text only knows its height once it has a width).
 */
private class InlayFrame(content: JComponent, private val editor: EditorEx) : JPanel(BorderLayout()) {
    var inlay: Inlay<*>? = null
    private var lastHeight = -1

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 6, 8)
        add(content)
    }

    override fun getPreferredSize(): Dimension {
        val visible = editor.scrollingModel.visibleArea.width
        val width = (if (visible > 0) visible - JBUI.scale(60) else JBUI.scale(640)).coerceIn(JBUI.scale(320), JBUI.scale(760))
        return Dimension(width, super.getPreferredSize().height)
    }

    override fun doLayout() {
        super.doLayout()
        val height = preferredSize.height
        if (height != lastHeight) {
            lastHeight = height
            SwingUtilities.invokeLater { inlay?.takeIf { it.isValid }?.update() }
        }
    }
}
