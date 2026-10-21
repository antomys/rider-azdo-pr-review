package io.github.antomys.azdoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupShowOptions
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.diff.ReviewDiff
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel.State
import io.github.antomys.azdoprreview.ui.Avatars
import io.github.antomys.azdoprreview.ui.MarkdownPane
import io.github.antomys.azdoprreview.ui.VoteIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The pull request's title, the changed files, and at the bottom where the review stands with the next step
 * ("Set Myself as Reviewer" or "Submit Review"). Double-clicking a file starts the review in the diff; once the diff is
 * open, selecting a file shows it there.
 */
class PullRequestDetailsPanel(
    private val project: Project,
    private val vm: PullRequestDetailsViewModel,
    private val scope: CoroutineScope,
    private val diff: ReviewDiff,
    onBack: () -> Unit,
    private val openTimeline: () -> Unit,
) : SimpleToolWindowPanel(true, true) {
    private val avatars = Avatars.of(vm.target.provider)
    private val header = JPanel(VerticalLayout(JBUI.scale(8))).apply { isOpaque = false }
    private val files = ReviewFilesTree(vm, scope) { diff.show(it, focus = true) }
    private var showDetails = false

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

        files.addTreeSelectionListener {
            val change = files.selectedChange() ?: return@addTreeSelectionListener
            if (change != vm.shownFile.value && diff.isOpen()) diff.show(change, focus = false)
        }
        val column = ScrollableColumn().apply {
            add(header)
            add(files)
        }
        setContent(JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(column, true).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            add(ReviewStatus(), BorderLayout.SOUTH)
        })

        scope.launch(Dispatchers.EDT) { vm.state.collect { renderHeader() } }
        scope.launch(Dispatchers.EDT) { vm.shownFile.collect { shown -> shown?.takeIf { it != files.selectedChange() }?.let(files::select) } }
    }

    private fun renderHeader() {
        header.removeAll()
        when (val state = vm.state.value) {
            State.Loading -> header.add(JBLabel("Loading…").apply { foreground = UIUtil.getContextHelpForeground() })
            is State.Failed -> header.add(JBLabel(state.message).apply { foreground = UIUtil.getErrorForeground() })
            is State.Loaded -> {
                val summary = state.details.summary
                // The number links to the pull request in the browser.
                header.add(MarkdownPane("**${escapeMarkdown(summary.title)}**&nbsp;&nbsp;[#${summary.ref.id}](${vm.webUrl()})").apply {
                    font = JBFont.h3()
                })
                header.add(ActionLink("View Timeline") { openTimeline() })
                header.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(JBLabel("Changes").apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
                    add(JBLabel("${summary.sourceBranch} → ${summary.targetBranch}", AllIcons.Vcs.Branch, SwingConstants.RIGHT).apply {
                        horizontalAlignment = SwingConstants.RIGHT
                        foreground = UIUtil.getContextHelpForeground()
                        toolTipText = "${summary.sourceBranch} into ${summary.targetBranch}"
                    }, BorderLayout.CENTER)
                })
                header.add(ActionLink(if (showDetails) "Hide details" else "Show details") {
                    showDetails = !showDetails
                    renderHeader()
                })
                if (showDetails) header.add(details(state))
            }
        }
        header.revalidate()
        header.repaint()
    }

    /** Author, state, description and reviewers. */
    private fun details(state: State.Loaded): JComponent {
        val summary = state.details.summary
        return JPanel(VerticalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(stateBadge(summary.state, summary.isDraft))
                add(JBLabel(
                    "${summary.author.displayName} opened ${DateFormatUtil.formatPrettyDateTime(summary.created.toEpochMilli())}",
                    avatars.icon(summary.author, AVATAR_SIZE, this), SwingConstants.LEADING,
                ).apply { foreground = UIUtil.getContextHelpForeground() })
            })
            if (state.details.description.isBlank()) {
                add(JBLabel("No description").apply { foreground = UIUtil.getContextHelpForeground() })
            } else {
                add(MarkdownPane(state.details.description))
            }
            add(JBLabel("Reviewers").apply { font = JBFont.label().asBold() })
            if (state.details.reviewers.isEmpty()) add(JBLabel("No reviewers yet").apply { foreground = UIUtil.getContextHelpForeground() })
            state.details.reviewers.forEach { reviewer ->
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBLabel(reviewer.user.displayName + if (reviewer.user.id == state.me.id) " (you)" else "", avatars.icon(reviewer.user, AVATAR_SIZE, this), SwingConstants.LEADING))
                    VoteIcons.of(reviewer.vote)?.let { add(JBLabel(it)) }
                    add(JBLabel(listOfNotNull(VoteIcons.NAMES[reviewer.vote], "required".takeIf { reviewer.isRequired }).joinToString(" · ")).apply {
                        foreground = UIUtil.getContextHelpForeground()
                    })
                })
            }
        }
    }

    /**
     * What the review is waiting for, one line each (conflicts, missing or pending reviewers, votes), then the next step:
     * "Set Myself as Reviewer" until the user reviews, then "Submit Review (drafts)…"; more actions are under ⋮.
     */
    private inner class ReviewStatus : JPanel(VerticalLayout(JBUI.scale(6))) {
        private val error = JBLabel().apply { foreground = UIUtil.getErrorForeground() }

        init {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(8, 12))
            isVisible = false
            scope.launch(Dispatchers.EDT) { combine(vm.state, vm.drafts, vm.threads) { state, _, _ -> state }.collect { render() } }
        }

        fun render() {
            val state = vm.state.value as? State.Loaded
            isVisible = state != null
            removeAll()
            if (state == null) return
            val summary = state.details.summary
            val reviewers = state.details.reviewers
            fun line(icon: Icon?, text: String, people: List<ReviewUser> = emptyList()) = add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                icon?.let { add(JBLabel(it)) }
                people.take(MAX_AVATARS).forEach { add(JBLabel(avatars.icon(it, SMALL_AVATAR, this))) }
                add(JBLabel(text))
            })
            fun names(people: List<ReviewUser>) = people.joinToString { it.displayName }

            when (summary.state) {
                ReviewState.MERGED -> line(AllIcons.Vcs.Merge, "Completed")
                ReviewState.CLOSED -> line(AllIcons.Actions.Cancel, "Abandoned")
                ReviewState.OPEN -> {
                    if (summary.hasConflicts) line(AllIcons.General.Error, "Merge conflicts: resolve them before merging")
                    if (summary.isDraft) line(AllIcons.General.Information, "Draft: votes are possible once it's published")
                    val byVote = reviewers.groupBy { it.vote }.mapValues { (_, list) -> list.map { it.user } }
                    if (reviewers.isEmpty()) line(AllIcons.General.Warning, "Missing reviewer")
                    byVote[Vote.REJECTED]?.let { line(VoteIcons.of(Vote.REJECTED), "Rejected by ${names(it)}", it) }
                    byVote[Vote.WAITING_FOR_AUTHOR]?.let { line(VoteIcons.of(Vote.WAITING_FOR_AUTHOR), "Changes requested by ${names(it)}", it) }
                    val approved = byVote[Vote.APPROVED].orEmpty() + byVote[Vote.APPROVED_WITH_SUGGESTIONS].orEmpty()
                    if (approved.isNotEmpty()) line(VoteIcons.of(Vote.APPROVED), "Approved by ${names(approved)}", approved)
                    byVote[Vote.NONE]?.let { line(AllIcons.General.Warning, "Waiting for review from ${names(it)}", it) }
                    add(buttons(state))
                }
            }
            error.text.takeIf { it.isNotEmpty() }?.let { add(error) }
            revalidate()
            repaint()
        }

        private fun buttons(state: State.Loaded) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            val drafts = vm.drafts.value.size
            val primary = if (!vm.isReviewer() && drafts == 0) {
                JButton("Set Myself as Reviewer").apply { addActionListener { send { vm.addMeAsReviewer() } } }
            } else {
                JButton(if (drafts > 0) "Submit Review ($drafts)…" else "Submit Review…").apply {
                    addActionListener { SubmitReviewPopup.show(project, vm, scope, this, above = true) }
                }
            }
            primary.putClientProperty("JButton.buttonType", "default")
            primary.isDefaultCapable = true
            add(primary)
            add(JButton(AllIcons.Actions.More).apply {
                toolTipText = "More actions"
                putClientProperty("JButton.buttonType", "square")
                preferredSize = Dimension(preferredSize.height, preferredSize.height)
                addActionListener { moreActions(this, state) }
            })
        }

        private fun moreActions(anchor: JComponent, state: State.Loaded) {
            val summary = state.details.summary
            val group = DefaultActionGroup(
                action("Request Review…") { chooseReviewer(anchor) },
                action("Merge…", enabled = !summary.isDraft && !summary.hasConflicts) {
                    val ok = Messages.showYesNoDialog(
                        project, "Merge ${summary.sourceBranch} into ${summary.targetBranch} and complete #${summary.ref.id}?",
                        "Merge Pull Request", "Merge", "Cancel", null,
                    ) == Messages.YES
                    if (ok) send { vm.complete() }
                },
                action("Rebase", enabled = false) {},
                action("Close Pull Request") {
                    val ok = Messages.showYesNoDialog(
                        project, "Abandon #${summary.ref.id} without merging?", "Close Pull Request", "Close", "Cancel", null,
                    ) == Messages.YES
                    if (ok) send { vm.abandon() }
                },
            )
            JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
                .show(PopupShowOptions.aboveComponent(anchor))
        }

        private fun chooseReviewer(anchor: JComponent) {
            scope.launch(Dispatchers.EDT) {
                val reviewers = (vm.state.value as? State.Loaded)?.details?.reviewers.orEmpty().map { it.user.id }.toSet()
                val people = vm.searchUsers("").filter { it.id !in reviewers }
                JBPopupFactory.getInstance().createPopupChooserBuilder(people)
                    .setTitle("Request Review From")
                    .setRenderer(textListCellRenderer { it?.displayName })
                    .setNamerForFiltering { it.displayName }
                    .setItemChosenCallback { user -> send { vm.requestReview(user) } }
                    .createPopup()
                    .show(PopupShowOptions.aboveComponent(anchor))
            }
        }

        private fun action(name: String, enabled: Boolean = true, perform: () -> Unit) = object : DumbAwareAction(name) {
            override fun actionPerformed(e: AnActionEvent) = perform()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        /** Runs a call to the service; its error stays on the panel until the next call. */
        private fun send(call: suspend () -> Unit) {
            scope.launch(Dispatchers.EDT) {
                error.text = ""
                try {
                    call()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.text = e.message ?: e.javaClass.simpleName
                }
                render()
            }
        }
    }

    private fun stateBadge(state: ReviewState, isDraft: Boolean): JComponent {
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
    private class ScrollableColumn : JPanel(VerticalLayout(JBUI.scale(10))), Scrollable {
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

    companion object {
        private const val AVATAR_SIZE = 20
        private const val SMALL_AVATAR = 16
        private const val MAX_AVATARS = 3

        private val MARKDOWN_SPECIAL = Regex("""[\\`*_{}\[\]()#+\-.!<>|~]""")

        fun escapeMarkdown(text: String) = text.replace(MARKDOWN_SPECIAL) { "\\" + it.value }
    }
}
