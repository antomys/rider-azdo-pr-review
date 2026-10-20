package io.github.antomys.azdoprreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** One discussion thread: its comments, a reply box, and resolve / reopen. */
class ThreadPanel(
    private val thread: ReviewThread,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    background: Color,
) : JPanel(VerticalLayout(JBUI.scale(10))) {
    private val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply { isOpaque = false }
    private val replyEditor = CommentEditor(scope, "Reply", "Reply…", submit = { vm.reply(thread, it) }, cancel = { showReply(false) })

    init {
        this.background = background
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(8, 10))
        val resolved = thread.status?.isResolved == true
        thread.comments.filterNot { it.isDeleted }.forEachIndexed { index, comment ->
            add(comment(comment, status = if (index == 0) thread.status?.let { statusLabel(it.id, it.isResolved) } else null))
        }
        footer.add(ActionLink("Reply") { showReply(true) })
        if (thread.status != null) {
            footer.add(ActionLink(if (resolved) "Reopen" else "Resolve") { setResolved(!resolved) })
        }
        add(footer)
        add(replyEditor.apply { isVisible = false })
    }

    private fun comment(comment: ReviewComment, status: JComponent?): JComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        isOpaque = false
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(comment.author.displayName, AllIcons.General.User, SwingConstants.LEADING).apply { font = JBFont.label().asBold() })
            add(JBLabel(DateFormatUtil.formatPrettyDateTime(comment.created.toEpochMilli())).apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            status?.let(::add)
        }
        add(header, BorderLayout.NORTH)
        add(MarkdownPane(comment.displayBody), BorderLayout.CENTER)
    }

    private fun statusLabel(id: String, isResolved: Boolean) = JBLabel(STATUS_NAMES[id] ?: id).apply {
        font = JBFont.small()
        foreground = if (isResolved) JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x5FB865)) else UIUtil.getContextHelpForeground()
        border = JBUI.Borders.compound(RoundedLineBorder(foreground, JBUI.scale(6)), JBUI.Borders.empty(0, 4))
    }

    private fun showReply(show: Boolean) {
        replyEditor.isVisible = show
        footer.isVisible = !show
        revalidate()
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
