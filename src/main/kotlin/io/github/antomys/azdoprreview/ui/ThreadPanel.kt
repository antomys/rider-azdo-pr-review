package io.github.antomys.azdoprreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.toolwindow.DraftComment
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * One discussion thread: the first comment, its replies indented below it, pending reply [drafts], a reply box, and
 * resolve / reopen. A resolved thread starts collapsed to one grey, struck-through line; clicking it expands it.
 * Replies are saved as drafts when [replyAsDraft] (with "Post Now" in the drop-down), otherwise sent at once.
 * [onLocation], when given, shows the thread's file and line as a link that calls it.
 */
class ThreadPanel(
    private val project: Project,
    private val thread: ReviewThread,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    background: Color,
    private val onLocation: ((ReviewThread) -> Unit)? = null,
    private val drafts: List<DraftComment> = emptyList(),
    replyAsDraft: Boolean = false,
) : JPanel(VerticalLayout(JBUI.scale(8))) {
    private val avatars = Avatars.of(vm.target.provider)
    private val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply { isOpaque = false }
    private val postNow = CommentAction("Post Now", "reply") { vm.reply(thread, it.encoded) }
    private val replyEditor = CommentComposer(
        project, scope, vm.me(), avatars, "Reply… (@ to mention)", Mentions(vm::searchUsers, vm::encodeMention),
        primary = if (replyAsDraft) CommentAction("Save as Draft", "add draft") { vm.addDraft(null, thread, it.text, it.mentioned) } else postNow,
        others = if (replyAsDraft) listOf(postNow) else emptyList(),
        cancel = { showReply(false) },
    )
    private val comments = thread.comments.filterNot { it.isDeleted }
    private val textColor = if (thread.isResolved) UIUtil.getContextHelpForeground() else null

    init {
        this.background = background
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(8, 10))
        footer.add(ActionLink("Reply") { showReply(true) })
        if (thread.status != null) {
            footer.add(ActionLink(if (thread.isResolved) "Reopen" else "Resolve") { setResolved(!thread.isResolved) })
        }
        // A pending reply keeps a resolved thread open, so the reply stays in sight.
        if (thread.isResolved && drafts.isEmpty()) showCollapsed() else showExpanded()
    }

    private fun showCollapsed() {
        removeAll()
        val summary = SimpleColoredComponent().apply {
            isOpaque = false
            icon = AllIcons.General.ArrowRight
            val first = comments.firstOrNull()
            val line = first?.displayBody?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
            val text = line.replace(MARKDOWN_MARKS, "").trim().take(120)
            append(text, SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getContextHelpForeground()))
            first?.let { append("  ${it.author.displayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            if (comments.size > 1) append("  ${comments.size - 1} ${if (comments.size == 2) "reply" else "replies"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            toolTipText = "Resolved thread: click to expand"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val row = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(summary, BorderLayout.CENTER)
            thread.status?.let { add(statusLabel(it.id, it.isResolved), BorderLayout.EAST) }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = showExpanded()
            })
        }
        summary.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showExpanded()
        })
        locationLink()?.let(::add)
        add(row)
        relayout()
    }

    private fun showExpanded() {
        removeAll()
        locationLink()?.let(::add)
        if (thread.isResolved) add(ActionLink("Collapse") { showCollapsed() }.apply { icon = AllIcons.General.ArrowDown })
        comments.firstOrNull()?.let { add(comment(it, thread.status?.let { s -> statusLabel(s.id, s.isResolved) })) }
        comments.drop(1).forEach { reply -> add(indented(comment(reply, null))) }
        drafts.forEach { add(indented(DraftPanel(project, it, vm, scope))) }
        add(footer)
        add(replyEditor.apply { isVisible = false })
        relayout()
    }

    /** A reply: indented, with a line on its left that ties it to the thread. */
    private fun indented(content: JComponent) = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.emptyLeft(14),
            JBUI.Borders.customLine(JBColor.border(), 0, 2, 0, 0),
            JBUI.Borders.emptyLeft(10),
        )
        add(content)
    }

    private fun relayout() {
        revalidate()
        repaint()
        // A thread inside a diff lives in an inlay that has to be told its size changed.
        parent?.revalidate()
    }

    /** "Name.cs:12" (or the file name for a file comment), linking to the thread in the diff. */
    private fun locationLink(): JComponent? {
        val callback = onLocation ?: return null
        val anchor = thread.anchor ?: return null
        val name = anchor.path.substringAfterLast('/')
        val text = anchor.lines?.let { lines -> "$name:${lines.start}" + if (lines.end != lines.start) "–${lines.end}" else "" } ?: name
        val canNavigate = vm.changeOf(thread) != null
        return ActionLink(text) { callback(thread) }.apply {
            icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
            toolTipText = if (canNavigate) "Show in the diff: ${anchor.path}" else "${anchor.path} isn't changed in the latest iteration"
            isEnabled = canNavigate
        }
    }

    private fun comment(comment: ReviewComment, status: JComponent?): JComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        isOpaque = false
        // Name, then the time, which shortens rather than disappears when the thread is narrow, then the status.
        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(comment.author.displayName, avatars.icon(comment.author, AVATAR_SIZE, this@ThreadPanel), SwingConstants.LEADING).plainText().apply {
                font = JBFont.label().asBold()
                textColor?.let { foreground = it }
            }, BorderLayout.WEST)
            add(timeLabel(comment.created, comment.updated), BorderLayout.CENTER)
            status?.let { add(it, BorderLayout.EAST) }
        }
        add(header, BorderLayout.NORTH)
        add(MarkdownPane(comment.displayBody, textColor), BorderLayout.CENTER)
    }

    private fun timeLabel(created: Instant, updated: Instant?) = JBLabel(
        pretty(created) + (updated?.let { " · updated ${pretty(it)}" } ?: ""),
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = "Posted ${DateFormatUtil.formatDateTime(created.toEpochMilli())}" +
            (updated?.let { "<br>Updated ${DateFormatUtil.formatDateTime(it.toEpochMilli())}" } ?: "")
        if (updated != null) toolTipText = "<html>$toolTipText</html>"
    }

    private fun pretty(time: Instant) = DateFormatUtil.formatPrettyDateTime(time.toEpochMilli())

    private fun statusLabel(id: String, isResolved: Boolean) = JBLabel(STATUS_NAMES[id] ?: id).apply {
        font = JBFont.small()
        foreground = if (isResolved) JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x5FB865)) else UIUtil.getContextHelpForeground()
        border = JBUI.Borders.compound(RoundedLineBorder(foreground, JBUI.scale(6)), JBUI.Borders.empty(0, 4))
    }

    private fun showReply(show: Boolean) {
        replyEditor.isVisible = show
        footer.isVisible = !show
        relayout()
        if (show) replyEditor.focus()
    }

    private fun setResolved(resolved: Boolean) {
        footer.components.forEach { it.isEnabled = false }
        scope.launch(Dispatchers.EDT) {
            try {
                vm.setResolved(thread, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                footer.components.forEach { it.isEnabled = true }
                footer.add(JBLabel(e.message ?: e.javaClass.simpleName).apply { foreground = UIUtil.getErrorForeground() })
                footer.revalidate()
            }
        }
    }

    private companion object {
        const val AVATAR_SIZE = 20

        /** Markdown punctuation left out of a one-line summary. */
        val MARKDOWN_MARKS = Regex("""[`*_~]|^\s*(#+|>|[-+]|\d+\.)\s+""")

        val STATUS_NAMES = mapOf(
            "active" to "Active",
            "pending" to "Pending",
            "fixed" to "Resolved",
            "wontFix" to "Won't fix",
            "closed" to "Closed",
            "byDesign" to "By design",
        )
    }
}
