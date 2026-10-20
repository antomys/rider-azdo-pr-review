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
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.ui.CommentField
import io.github.antomys.azdoprreview.ui.MarkdownPane
import io.github.antomys.azdoprreview.ui.Mentions
import io.github.antomys.azdoprreview.ui.ThreadPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Overview (description, reviewers, comments) above the changes tree, and the review box (comment and vote) at the bottom.
 * The first file opens in the diff when the pull request first loads.
 */
class PullRequestDetailsPanel(
    private val project: Project,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    onBack: () -> Unit,
) : SimpleToolWindowPanel(true, true) {
    private val diff = ReviewDiff(project, vm, scope)
    private val mentions = Mentions(vm::searchUsers, vm::encodeMention)
    private val overview = ScrollableColumn()
    private val discussion = Discussion()
    private val reviewBox = ReviewBox()
    private var openedFirstFile = false

    init {
        val back = object : DumbAwareAction("Back to Pull Requests", "Show the pull request list", AllIcons.Actions.Back) {
            override fun actionPerformed(e: AnActionEvent) = onBack()

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
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
        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPrDetails", DefaultActionGroup(back, refresh, browse), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(
            JPanel(BorderLayout()).apply {
                add(
                    OnePixelSplitter(true, 0.55f).apply {
                        firstComponent = ScrollPaneFactory.createScrollPane(overview, true).apply {
                            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        }
                        secondComponent = ReviewChangesPanel(vm, scope, diff)
                    },
                    BorderLayout.CENTER,
                )
                add(reviewBox, BorderLayout.SOUTH)
            },
        )

        scope.launch(Dispatchers.EDT) { vm.state.collect(::render) }
    }

    private fun render(state: State) {
        overview.removeAll()
        when (state) {
            State.Loading -> overview.add(JBLabel("Loading…").apply { foreground = UIUtil.getContextHelpForeground() })
            is State.Failed -> overview.add(JBLabel(state.message).apply { foreground = UIUtil.getErrorForeground() })
            is State.Loaded -> {
                overview.add(overview(state))
                overview.add(discussion)
                if (!openedFirstFile) {
                    openedFirstFile = true
                    state.changes.firstOrNull()?.let { diff.show(it, focus = false) }
                }
            }
        }
        overview.revalidate()
        overview.repaint()
    }

    private fun overview(state: State.Loaded): JComponent {
        val details = state.details
        val summary = details.summary
        return panel {
            row {
                // The number links to the pull request in the browser.
                cell(MarkdownPane("**${escapeMarkdown(summary.title)}**&nbsp;&nbsp;[#${summary.ref.id}](${vm.webUrl()})").apply {
                    font = JBFont.h3()
                    toolTipText = "Click the number to open the pull request in Azure DevOps"
                }).align(AlignX.FILL)
            }
            row {
                cell(badge(summary.state, summary.isDraft))
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
            }
        }.apply { isOpaque = false }
    }

    /** Opens the thread's file in the diff and scrolls to the thread. */
    private fun showThread(thread: ReviewThread) {
        val change = vm.changeOf(thread) ?: return
        vm.navigateTo(thread)
        diff.show(change, focus = true)
    }

    /**
     * The pull request's threads, oldest first: all of them or only the file shown in the diff;
     * resolved threads are hidden unless asked for.
     */
    private inner class Discussion : JPanel(VerticalLayout(JBUI.scale(8))) {
        private val title = TitledSeparator("Comments")
        private val list = JPanel(VerticalLayout(JBUI.scale(8))).apply { isOpaque = false }
        private val perFile = MutableStateFlow(false)
        private val showResolved = MutableStateFlow(false)
        private val resolvedBox = JBCheckBox("Show resolved").apply {
            isOpaque = false
            addActionListener { showResolved.value = isSelected }
        }

        init {
            isOpaque = false
            val controls = panel {
                row {
                    segmentedButton(listOf(false, true)) { text = if (it) "Current File" else "All" }.apply {
                        selectedItem = false
                        whenItemSelected { perFile.value = it }
                    }
                    cell(resolvedBox)
                }
            }.apply { isOpaque = false }
            add(title)
            add(controls)
            add(list)
            scope.launch(Dispatchers.EDT) {
                combine(vm.threads, vm.shownFile, perFile, showResolved) { threads, shown, perFile, resolved ->
                    Triple(threads, if (perFile) shown else null, perFile to resolved)
                }.collect { (threads, file, options) -> render(threads, file, options.first, options.second) }
            }
        }

        private fun render(threads: List<ReviewThread>, file: FileChange?, perFile: Boolean, showResolved: Boolean) {
            val all = PullRequestDetailsViewModel.discussion(threads, file, showResolved = true)
            val resolved = all.count { it.isResolved }
            val where = if (perFile) file?.path?.substringAfterLast('/')?.let { " on $it" } ?: "" else ""
            title.text = "Comments$where · ${all.size - resolved} open" + if (resolved > 0) ", $resolved resolved" else ""
            resolvedBox.text = "Show resolved ($resolved)"
            list.removeAll()
            val shown = PullRequestDetailsViewModel.discussion(threads, file, showResolved)
            val empty = when {
                perFile && file == null -> "Open a file to see its comments"
                shown.isEmpty() && all.isNotEmpty() -> "No open comments"
                shown.isEmpty() -> "No comments yet"
                else -> null
            }
            empty?.let { list.add(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
            if (!(perFile && file == null)) {
                shown.forEach { list.add(ThreadPanel(project, it, vm, scope, UIUtil.getPanelBackground(), onLocation = ::showThread)) }
            }
            list.revalidate()
            list.repaint()
        }
    }

    /**
     * Comment and vote, pinned below the changes. The main button approves, or approves with suggestions while the user's
     * own threads are open; a comment typed above is posted before the vote, or on its own with Comment.
     */
    private inner class ReviewBox : JPanel(BorderLayout(0, JBUI.scale(6))) {
        private val field = CommentField(project, scope, "Comment on the pull request, with or without a vote (@ to mention)", mentions)
        private val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        private val status = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            horizontalAlignment = SwingConstants.RIGHT
        }
        private val error = JBLabel().apply { foreground = UIUtil.getErrorForeground() }
        private var busy = false

        init {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(8, 12))
            isVisible = false
            field.onSubmit = { comment() }
            add(field, BorderLayout.CENTER)
            // The vote status shortens before it would cover the buttons; errors get their own line.
            add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(buttons, BorderLayout.WEST)
                    add(status, BorderLayout.CENTER)
                })
                add(error)
            }, BorderLayout.SOUTH)
            scope.launch(Dispatchers.EDT) { combine(vm.state, vm.threads) { state, _ -> state }.collect(::update) }
        }

        private fun update(state: State) {
            val loaded = state as? State.Loaded
            isVisible = loaded != null
            if (loaded == null) return
            val suggested = vm.suggestedVote()
            val current = vm.myVote()
            fun action(vote: Vote) = object : AbstractAction(VOTE_ACTIONS.getValue(vote)) {
                override fun actionPerformed(e: ActionEvent?) = submit(vote)
            }
            val options = (VOTE_ACTIONS.keys - suggested - Vote.NONE).map(::action) + listOfNotNull(action(Vote.NONE).takeIf { current != Vote.NONE })
            val isDraft = loaded.details.summary.isDraft
            buttons.removeAll()
            buttons.add(JBOptionButton(action(suggested), options.toTypedArray<Action>()).apply {
                isEnabled = !busy && !isDraft
                toolTipText = when {
                    isDraft -> "Draft pull requests can't be voted on"
                    suggested == Vote.APPROVED_WITH_SUGGESTIONS -> "You have open comments, so this approves with suggestions; the comment above is posted first"
                    else -> "Vote; the comment above is posted first"
                }
            })
            buttons.add(JButton("Comment").apply {
                isEnabled = !busy
                toolTipText = "Post the comment without voting (${if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"})"
                addActionListener { comment() }
            })
            status.text = if (current == Vote.NONE) "You haven't voted" else "Your vote: ${VOTE_NAMES[current]}"
            status.icon = voteIcon(current).takeIf { current != Vote.NONE }
            buttons.revalidate()
            buttons.repaint()
        }

        private fun submit(vote: Vote) = send { vm.submitReview(vote, it) }

        private fun comment() {
            if (field.text.isNotBlank()) send { vm.createThread(null, it) }
        }

        private fun send(action: suspend (String) -> Unit) {
            if (busy) return
            setBusy(true)
            error.text = ""
            scope.launch(Dispatchers.EDT) {
                try {
                    action(field.text)
                    field.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.text = e.message ?: e.javaClass.simpleName
                } finally {
                    setBusy(false)
                }
            }
        }

        private fun setBusy(value: Boolean) {
            busy = value
            field.isEnabled = !value
            update(vm.state.value)
        }
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
        /** Button text per vote, in the drop-down's order; [Vote.NONE] resets the vote. */
        val VOTE_ACTIONS = linkedMapOf(
            Vote.APPROVED to "Approve",
            Vote.APPROVED_WITH_SUGGESTIONS to "Approve with Suggestions",
            Vote.WAITING_FOR_AUTHOR to "Wait for Author",
            Vote.REJECTED to "Reject",
            Vote.NONE to "Reset Vote",
        )

        private val MARKDOWN_SPECIAL = Regex("""[\\`*_{}\[\]()#+\-.!<>|~]""")

        fun escapeMarkdown(text: String) = text.replace(MARKDOWN_SPECIAL) { "\\" + it.value }

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
