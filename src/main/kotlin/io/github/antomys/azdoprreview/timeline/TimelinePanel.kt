package io.github.antomys.azdoprreview.timeline

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.toolwindow.DraftComment
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsPanel.Companion.escapeMarkdown
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.ui.Avatars
import io.github.antomys.azdoprreview.ui.CommentAction
import io.github.antomys.azdoprreview.ui.CommentComposer
import io.github.antomys.azdoprreview.ui.DraftPanel
import io.github.antomys.azdoprreview.ui.MarkdownPane
import io.github.antomys.azdoprreview.ui.Mentions
import io.github.antomys.azdoprreview.ui.ThreadPanel
import io.github.antomys.azdoprreview.ui.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The title, the description, then every thread and event oldest first; system events are one grey line each.
 * A comment box at the bottom posts to the pull request.
 */
class TimelinePanel(
    private val project: Project,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    private val diff: ReviewDiff,
) : JPanel(BorderLayout()) {
    private val avatars = Avatars.of(vm.target.provider)
    private val column = Column()

    init {
        background = UIUtil.getPanelBackground()
        add(ScrollPaneFactory.createScrollPane(column, true).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }, BorderLayout.CENTER)
        val mentions = Mentions(vm::searchUsers, vm::encodeMention)
        val composer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(10, 16))
            isVisible = false
        }
        add(composer, BorderLayout.SOUTH)
        scope.launch(Dispatchers.EDT) {
            combine(vm.state, vm.threads, vm.drafts) { state, threads, drafts -> Triple(state, threads, drafts) }
                .collect { (state, threads, drafts) ->
                    render(state, threads, drafts)
                    if (state is State.Loaded && !composer.isVisible) {
                        composer.isVisible = true
                        composer.add(CommentComposer(
                            project, scope, state.me, avatars, "Comment on the pull request (@ to mention)", mentions,
                            primary = CommentAction("Comment", "comment") { vm.createThread(null, it.encoded) },
                            others = listOf(CommentAction("Save as Draft", "save as draft") { vm.addDraft(null, null, it.text, it.mentioned) }),
                            minLines = 2,
                        ))
                    }
                }
        }
    }

    private fun render(state: State, threads: List<ReviewThread>, drafts: List<DraftComment>) {
        column.removeAll()
        when (state) {
            State.Loading -> column.add(JBLabel("Loading…").apply { foreground = UIUtil.getContextHelpForeground() })
            is State.Failed -> column.add(JBLabel(state.message).apply { foreground = UIUtil.getErrorForeground() })
            is State.Loaded -> {
                val summary = state.details.summary
                column.add(MarkdownPane("**${escapeMarkdown(summary.title)}** <font color=\"#888888\">#${summary.ref.id}</font>").apply {
                    font = JBFont.h2()
                })
                column.add(item(summary.author, summary.created, "opened the pull request", if (state.details.description.isBlank()) {
                    JBLabel("No description").apply { foreground = UIUtil.getContextHelpForeground() }
                } else {
                    MarkdownPane(state.details.description)
                }))
                threads.filter { t -> t.isSystem || t.comments.any { !it.isDeleted } }.sortedBy { it.created ?: Instant.MAX }.forEach { thread ->
                    column.add(if (thread.isSystem) event(thread) else card(ThreadPanel(
                        project, thread, vm, scope, UIUtil.getPanelBackground(), onLocation = ::showThread,
                        drafts = drafts.filter { it.replyTo == thread.id },
                    )))
                }
                drafts.filter { it.replyTo == null && it.anchor == null }.forEach { column.add(card(DraftPanel(project, it, vm, scope))) }
            }
        }
        column.revalidate()
        column.repaint()
    }

    /** Opens the thread's file in the diff and scrolls to the thread. */
    private fun showThread(thread: ReviewThread) {
        val change = vm.changeOf(thread) ?: return
        vm.navigateTo(thread)
        diff.show(change, focus = true)
    }

    /** Avatar, "Name action · time", then [content]. */
    private fun item(author: ReviewUser, time: Instant, action: String, content: JComponent) = card(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        add(JBLabel(avatars.icon(author, AVATAR_SIZE, this)).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
        add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel("<html><b>${StringUtil.escapeXmlEntities(author.displayName)}</b> $action · ${DateFormatUtil.formatPrettyDateTime(time.toEpochMilli())}</html>"))
            add(content)
        }, BorderLayout.CENTER)
    })

    /** A pushed change, vote or similar: one grey line with a blue bar on its left. */
    private fun event(thread: ReviewThread): JComponent {
        val comment = thread.comments.first()
        val time = DateFormatUtil.formatPrettyDateTime(comment.created.toEpochMilli())
        return JBLabel("${comment.displayBody.lineSequence().first()} · $time").plainText().apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.compound(
                JBUI.Borders.emptyLeft(16),
                JBUI.Borders.customLine(JBColor(0x3574F0, 0x548AF7), 0, 2, 0, 0),
                JBUI.Borders.empty(2, 10),
            )
        }
    }

    private fun card(content: JComponent) = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        add(content)
    }

    /** Readable width, centred-left like a document; follows the viewport so text wraps. */
    private class Column : JPanel(VerticalLayout(JBUI.scale(12))), Scrollable {
        init {
            border = JBUI.Borders.empty(16, 24)
            background = UIUtil.getPanelBackground()
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        const val AVATAR_SIZE = 28
    }
}
