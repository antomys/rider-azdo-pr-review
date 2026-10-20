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
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.ui.CommentEditor
import io.github.antomys.azdoprreview.ui.MarkdownPane
import io.github.antomys.azdoprreview.ui.ThreadPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** Overview (description, reviewers, vote, comments) above the changes tree. */
class PullRequestDetailsPanel(
    project: Project,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
) : SimpleToolWindowPanel(true, true) {
    private val overview = ScrollableColumn()
    private val comments = JPanel(VerticalLayout(JBUI.scale(8))).apply { isOpaque = false }
    private val commentsTitle = TitledSeparator("Comments")
    private val newComment = CommentEditor(scope, "Comment", "Add a comment to the pull request…", submit = { vm.createThread(null, it) })
    private val voteError = JBLabel().apply { foreground = UIUtil.getErrorForeground() }

    init {
        val refresh = object : DumbAwareAction("Refresh", "Reload the pull request", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                vm.refresh()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val browse = object : DumbAwareAction("Open in Browser", "Open the pull request in Azure DevOps", AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) {
                BrowserUtil.browse(vm.webUrl())
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrDetails", DefaultActionGroup(refresh, browse), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(
            OnePixelSplitter(true, 0.55f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(overview, true).apply {
                    horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                }
                secondComponent = ReviewChangesPanel(project, vm, scope)
            },
        )

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
        scope.launch(Dispatchers.EDT) { vm.threads.collect(::renderComments) }
    }

    private fun render(state: State) {
        overview.removeAll()
        when (state) {
            State.Loading -> overview.add(JBLabel("Loading…").apply { foreground = UIUtil.getContextHelpForeground() })
            is State.Failed -> overview.add(JBLabel(state.message).apply { foreground = UIUtil.getErrorForeground() })
            is State.Loaded -> {
                overview.add(overview(state))
                overview.add(commentsTitle)
                overview.add(comments)
                overview.add(newComment)
            }
        }
        overview.revalidate()
        overview.repaint()
    }

    private fun overview(state: State.Loaded): JComponent {
        val details = state.details
        val summary = details.summary
        val myVote = details.reviewers.firstOrNull { it.user.id == state.me.id }?.vote ?: Vote.NONE
        return panel {
            row {
                cell(JBLabel(summary.title).apply {
                    font = JBFont.h3().asBold()
                    setAllowAutoWrapping(true)
                    setCopyable(true)
                }).align(AlignX.FILL)
            }
            row {
                cell(badge(summary.state, summary.isDraft))
                label("#${summary.ref.id}").applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
            row {
                label("${summary.author.displayName} opened ${DateFormatUtil.formatPrettyDateTime(summary.created.toEpochMilli())}")
                    .applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
            row {
                icon(AllIcons.Vcs.Branch)
                label("${summary.sourceBranch} → ${summary.targetBranch}")
            }
            group("Description") {
                row {
                    if (details.description.isBlank()) {
                        label("No description").applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                    } else {
                        cell(MarkdownPane(details.description)).align(AlignX.FILL)
                    }
                }
            }
            group("Reviewers") {
                if (details.reviewers.isEmpty()) {
                    row { label("No reviewers yet").applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
                }
                details.reviewers.forEach { reviewer ->
                    row {
                        icon(voteIcon(reviewer.vote))
                        label(reviewer.user.displayName + if (reviewer.user.id == state.me.id) " (you)" else "")
                        comment(listOfNotNull(VOTE_NAMES[reviewer.vote], "required".takeIf { reviewer.isRequired }).joinToString(" · "))
                    }
                }
                row {
                    cell(voteButton(myVote))
                    cell(voteError)
                }
            }
        }.apply { isOpaque = false }
    }

    /** Approve as the main action; the other votes, and reset, in its drop-down. */
    private fun voteButton(current: Vote): JComponent {
        fun action(name: String, vote: Vote) = object : AbstractAction(name) {
            override fun actionPerformed(e: ActionEvent?) = vote(vote)
        }
        val options = listOfNotNull(
            action("Approve with Suggestions", Vote.APPROVED_WITH_SUGGESTIONS),
            action("Wait for Author", Vote.WAITING_FOR_AUTHOR),
            action("Reject", Vote.REJECTED),
            action("Reset Vote", Vote.NONE).takeIf { current != Vote.NONE },
        )
        return JBOptionButton(action("Approve", Vote.APPROVED), options.toTypedArray<Action>()).apply {
            toolTipText = if (current == Vote.NONE) "You haven't voted" else "Your vote: ${VOTE_NAMES[current]}"
        }
    }

    private fun vote(vote: Vote) {
        voteError.text = ""
        scope.launch(Dispatchers.EDT) {
            try {
                vm.vote(vote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voteError.text = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun renderComments(threads: List<ReviewThread>) {
        val general = threads.filter { it.anchor == null && !it.isSystem && it.comments.any { c -> !c.isDeleted } }
        val onFiles = threads.count { it.anchor != null && !it.isSystem }
        commentsTitle.text = "Comments (${general.size})" + if (onFiles > 0) " · $onFiles on files" else ""
        comments.removeAll()
        general.forEach { comments.add(ThreadPanel(it, vm, scope, UIUtil.getPanelBackground())) }
        comments.revalidate()
        comments.repaint()
    }

    private fun badge(state: ReviewState, isDraft: Boolean): JComponent {
        val (text, color) = when {
            isDraft -> "Draft" to UIUtil.getContextHelpForeground()
            state == ReviewState.OPEN -> "Active" to JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x5FB865))
            state == ReviewState.MERGED -> "Completed" to JBColor(0x7A4FCF, 0xA885F2)
            else -> "Abandoned" to UIUtil.getContextHelpForeground()
        }
        return JBLabel(text).apply {
            foreground = color
            font = JBFont.small().asBold()
            border = JBUI.Borders.compound(RoundedLineBorder(color, JBUI.scale(8)), JBUI.Borders.empty(1, 6))
        }
    }

    /** A vertical stack that follows the viewport width, so text wraps instead of scrolling sideways. */
    private class ScrollableColumn : JPanel(VerticalLayout(JBUI.scale(12))), Scrollable {
        init {
            border = JBUI.Borders.empty(10, 12)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val VOTE_NAMES = mapOf(
            Vote.APPROVED to "Approved",
            Vote.APPROVED_WITH_SUGGESTIONS to "Approved with suggestions",
            Vote.WAITING_FOR_AUTHOR to "Waiting for author",
            Vote.REJECTED to "Rejected",
        )

        fun voteIcon(vote: Vote) = when (vote) {
            Vote.APPROVED -> AllIcons.RunConfigurations.TestPassed
            Vote.APPROVED_WITH_SUGGESTIONS -> AllIcons.General.InspectionsOK
            Vote.WAITING_FOR_AUTHOR -> AllIcons.General.Warning
            Vote.REJECTED -> AllIcons.RunConfigurations.TestFailed
            Vote.NONE -> AllIcons.General.User
        }
    }
}
