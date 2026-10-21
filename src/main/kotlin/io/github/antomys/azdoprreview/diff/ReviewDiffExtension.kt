package io.github.antomys.azdoprreview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
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
import io.github.antomys.azdoprreview.toolwindow.DraftComment
import io.github.antomys.azdoprreview.ui.Avatars
import io.github.antomys.azdoprreview.ui.CommentAction
import io.github.antomys.azdoprreview.ui.CommentComposer
import io.github.antomys.azdoprreview.ui.DraftPanel
import io.github.antomys.azdoprreview.ui.Mentions
import io.github.antomys.azdoprreview.ui.ThreadPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import com.intellij.diff.util.Side as EditorSide

/**
 * Shows a pull request file's threads and pending drafts inside its diff, a comment marker in the gutter of each line
 * that has them, and a gutter "+" to start new ones (saved as drafts unless posted at once).
 * Side-by-side diffs, the one-sided view of added or deleted files and the unified view are supported.
 */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val file = request.getUserData(PullRequestDiff.FILE) ?: return
        // Viewers are created for cached requests too, so this follows every switch between files.
        file.vm.onDiffShown(file.change)
        val editors: (EditorSide) -> EditorEx? = when (viewer) {
            is TwosideTextDiffViewer -> viewer::getEditor
            is OnesideTextDiffViewer -> { side -> viewer.editor.takeIf { side == viewer.side } }
            is UnifiedDiffViewer -> { _ -> viewer.editor }
            else -> return
        }
        DiffThreads(viewer, editors, file, (viewer as? UnifiedDiffViewer)?.let(UnifiedLines::of)).install()
    }
}

/**
 * The review UI of one diff viewer, disposed with it. [unified] is the line mapping of a unified viewer, whose one editor
 * shows both sides; it is null when each side has its own editor and editor lines are that side's lines.
 */
private class DiffThreads(
    private val viewer: FrameDiffTool.DiffViewer,
    private val editors: (EditorSide) -> EditorEx?,
    private val file: ReviewDiffFile,
    private val unified: UnifiedLines?,
) {
    private val change = file.change
    private val threadInlays = mutableListOf<Inlay<*>>()
    private val markers = mutableListOf<Pair<EditorEx, RangeHighlighter>>()

    /** True once the viewer has computed the diff and done its own initial scrolling. */
    private var ready = false

    fun install() {
        val jobs = listOf(
            file.scope.launch(Dispatchers.EDT) { combine(file.vm.threads, file.vm.drafts, ::Pair).collect { (threads, drafts) -> render(threads, drafts) } },
            file.scope.launch(Dispatchers.EDT) { file.vm.pendingNavigation.collect { if (ready) scrollToPending() } },
        )
        (viewer as? DiffViewerBase)?.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                // The unified viewer rewrites its document on every rediff, so its threads are laid out again.
                if (unified != null) render(file.vm.threads.value, file.vm.drafts.value)
                if (ready) return
                ready = true
                SwingUtilities.invokeLater(::scrollToPending)
            }
        }) ?: run { ready = true }
        Disposer.register(viewer) {
            jobs.forEach { it.cancel() }
            threadInlays.forEach(Disposer::dispose)
            clearMarkers()
        }
        if (unified == null) {
            Side.entries.forEach { side -> editor(side)?.let { editor -> installGutter(editor) { side } } }
        } else {
            (editor(Side.RIGHT) ?: editor(Side.LEFT))?.let { editor ->
                installGutter(editor) { line -> unified.sideAt(line)?.side?.takeIf { path(it) != null } }
            }
        }
    }

    private fun isOnThisFile(thread: ReviewThread) = thread.anchor?.path?.let { it == change.newPath || it == change.oldPath } == true

    private fun sideOf(thread: ReviewThread) = thread.anchor?.lines?.side ?: if (change.newPath != null) Side.RIGHT else Side.LEFT

    /** Scrolls to the thread the user asked to see, if it's on this file. */
    private fun scrollToPending() {
        val thread = file.vm.pendingNavigation.value?.takeIf(::isOnThisFile) ?: return
        val side = sideOf(thread)
        val editor = editor(side) ?: return
        val line = ((editorLine(side, thread.anchor?.lines?.start ?: 1) ?: 1) - 1).coerceIn(0, maxOf(editor.document.lineCount - 1, 0))
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        file.vm.navigated(thread)
    }

    private fun path(side: Side) = if (side == Side.LEFT) change.oldPath else change.newPath

    /** The 1-based editor line that shows 1-based [line] of [side]: the same line unless the view is unified; null if not shown. */
    private fun editorLine(side: Side, line: Int): Int? =
        if (unified == null) line else unified.toUnified(side, line - 1).takeIf { it >= 0 }?.plus(1)

    private fun editor(side: Side): EditorEx? =
        path(side)?.let { editors(if (side == Side.LEFT) EditorSide.LEFT else EditorSide.RIGHT) }

    private fun isOnThisFile(draft: DraftComment) =
        draft.replyTo == null && draft.anchor?.path?.let { it == change.newPath || it == change.oldPath } == true

    private fun render(threads: List<ReviewThread>, drafts: List<DraftComment>) {
        threadInlays.forEach(Disposer::dispose)
        threadInlays.clear()
        clearMarkers()
        threads.filter { !it.isSystem && isOnThisFile(it) && it.comments.any { c -> !c.isDeleted } }.forEach { thread ->
            val lines = thread.anchor!!.lines
            val side = sideOf(thread)
            val editor = editor(side) ?: return@forEach
            val line = lines?.let { editorLine(side, it.end) ?: return@forEach }
            val replies = drafts.filter { it.replyTo == thread.id }
            val panel = ThreadPanel(file.project, thread, file.vm, file.scope, UIUtil.getPanelBackground(), drafts = replies, replyAsDraft = true)
            threadInlays += addInlay(editor, panel, line, above = lines == null)
            line?.let { mark(editor, it, thread.comments.count { c -> !c.isDeleted } + replies.size) }
        }
        drafts.filter(::isOnThisFile).forEach { draft ->
            val lines = draft.anchor!!.lines
            val side = lines?.side ?: if (change.newPath != null) Side.RIGHT else Side.LEFT
            val editor = editor(side) ?: return@forEach
            val line = lines?.let { editorLine(side, it.end) ?: return@forEach }
            threadInlays += addInlay(editor, boxed(DraftPanel(file.project, draft, file.vm, file.scope)), line, above = lines == null)
            line?.let { mark(editor, it, 1) }
        }
    }

    /** A comment bubble in the gutter of 1-based [line]. */
    private fun mark(editor: EditorEx, line: Int, comments: Int) {
        val index = line - 1
        if (index !in 0 until editor.document.lineCount) return
        val highlighter = editor.markupModel.addLineHighlighter(index, HighlighterLayer.LAST, null)
        highlighter.gutterIconRenderer = CommentMarker(comments)
        markers += editor to highlighter
    }

    private fun clearMarkers() {
        // A unified rediff replaces the document, which can already have removed a highlighter.
        markers.forEach { (editor, highlighter) -> if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter) }
        markers.clear()
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

    /** Shows a "+" on the hovered line of [editor]; [sideAt] tells which side a comment on a 0-based editor line goes to. */
    private fun installGutter(editor: EditorEx, sideAt: (Int) -> Side?) {
        var hover: RangeHighlighter? = null
        fun clear() {
            hover?.takeIf { it.isValid }?.let(editor.markupModel::removeHighlighter)
            hover = null
        }
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val line = e.logicalPosition.line
                if (line >= editor.document.lineCount) return clear()
                val side = sideAt(line) ?: return clear()
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

    /** Opens a new-thread editor below editor [line] (0-based), covering the selection when it includes that line. */
    private fun startThread(side: Side, line: Int) {
        val editor = editor(side) ?: return
        val path = path(side) ?: return
        val selection = editor.selectionModel
        val document = editor.document
        var (start, end) = if (selection.hasSelection()) {
            val first = document.getLineNumber(selection.selectionStart)
            var last = document.getLineNumber(selection.selectionEnd)
            if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
            if (line in first..last) first to last else line to line
        } else {
            line to line
        }
        if (unified != null) {
            // Those are unified lines; the comment goes on the clicked line's side (see UnifiedLines.commentRange).
            val range = unified.commentRange(side, start, end) ?: return
            start = range.first
            end = range.last
        }
        val below = editorLine(side, end + 1) ?: return
        var inlay: Inlay<*>? = null
        val anchor = ThreadAnchor(path, LineRange(side, start + 1, end + 1))
        val composer = CommentComposer(
            file.project, file.scope, file.vm.me(), Avatars.of(file.vm.target.provider),
            "Comment on ${if (start == end) "line ${end + 1}" else "lines ${start + 1}–${end + 1}"}… (@ to mention)",
            Mentions(file.vm::searchUsers, file.vm::encodeMention),
            primary = CommentAction("Save as Draft", "add draft") {
                file.vm.addDraft(anchor, null, it.text, it.mentioned)
                inlay?.let(Disposer::dispose)
            },
            others = listOf(CommentAction("Post Now", "post") {
                file.vm.createThread(anchor, it.encoded)
                inlay?.let(Disposer::dispose)
            }),
            cancel = { inlay?.let(Disposer::dispose) },
        )
        inlay = addInlay(editor, boxed(composer), below, above = false)
        Disposer.register(viewer, inlay)
        SwingUtilities.invokeLater { composer.focus() }
    }

    private fun boxed(content: JComponent) = JPanel(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(8, 10))
        add(content)
    }

    private class CommentMarker(private val comments: Int) : GutterIconRenderer() {
        override fun getIcon() = AllIcons.General.Balloon
        override fun getTooltipText() = if (comments == 1) "1 comment" else "$comments comments"
        override fun getAlignment() = Alignment.RIGHT
        override fun equals(other: Any?) = other is CommentMarker && other.comments == comments
        override fun hashCode() = comments
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
