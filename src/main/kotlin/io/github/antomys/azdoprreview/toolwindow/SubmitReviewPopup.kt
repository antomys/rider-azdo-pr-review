package io.github.antomys.azdoprreview.toolwindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupShowOptions
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.Vote
import io.github.antomys.azdoprreview.ui.CommentField
import io.github.antomys.azdoprreview.ui.Mentions
import io.github.antomys.azdoprreview.ui.VoteIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * "Submit Review": an optional summary comment and how to finish. Every button sends the pending drafts first, then the
 * summary, then the vote; Comment sends without voting.
 */
object SubmitReviewPopup {
    /** Opens below [anchor], or above it when [above] (for buttons at the bottom of a panel). */
    fun show(project: Project, vm: PullRequestDetailsViewModel, scope: CoroutineScope, anchor: Component, above: Boolean = false) {
        val loaded = vm.state.value as? PullRequestDetailsViewModel.State.Loaded ?: return
        val field = CommentField(project, scope, "Summary comment (optional, @ to mention)", Mentions(vm::searchUsers, vm::encodeMention))
        val error = JBLabel().apply {
            foreground = UIUtil.getErrorForeground()
            isVisible = false
        }
        val drafts = vm.drafts.value.size
        val buttons = mutableListOf<JComponent>()
        var popup: JBPopup? = null

        fun submit(vote: Vote?) {
            buttons.forEach { it.isEnabled = false }
            field.isEnabled = false
            scope.launch(Dispatchers.EDT) {
                try {
                    vm.submitReview(vote, field.text)
                    popup?.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.text = e.message ?: e.javaClass.simpleName
                    error.isVisible = true
                    buttons.forEach { it.isEnabled = true }
                    field.isEnabled = true
                    popup?.pack(true, true)
                }
            }
        }

        fun action(vote: Vote?) = object : AbstractAction(VOTE_ACTIONS.getValue(vote)) {
            override fun actionPerformed(e: ActionEvent?) = submit(vote)
        }

        val canVote = !loaded.details.summary.isDraft
        val approve = vm.suggestedVote()
        val other = if (approve == Vote.APPROVED) Vote.APPROVED_WITH_SUGGESTIONS else Vote.APPROVED
        buttons += JBOptionButton(action(approve), arrayOf<Action>(action(other), action(Vote.REJECTED))).apply {
            isEnabled = canVote
        }
        buttons += JButton(action(Vote.WAITING_FOR_AUTHOR)).apply {
            isEnabled = canVote
            toolTipText = "Votes \"Wait for author\""
        }
        buttons += JButton(action(null)).apply { toolTipText = "Send the comments without voting" }

        val current = vm.myVote()
        val panel = JPanel(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            add(JBLabel("Submit Review").apply { font = JBFont.h4() })
            add(JBLabel(
                when (drafts) {
                    0 -> "No pending comments"
                    1 -> "1 pending comment will be posted"
                    else -> "$drafts pending comments will be posted"
                },
            ).apply { foreground = UIUtil.getContextHelpForeground() })
            // The field only knows its height once its editor exists, so the width is set without fixing the height.
            add(object : JPanel(BorderLayout()) {
                override fun getPreferredSize() = super.getPreferredSize().let { Dimension(maxOf(it.width, JBUI.scale(440)), it.height) }
            }.apply { add(field) })
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { buttons.forEach(::add) })
            if (!canVote) add(JBLabel("Draft pull requests can't be voted on").apply { foreground = UIUtil.getContextHelpForeground() })
            if (current != Vote.NONE) {
                add(JBLabel("Your current vote: ${VoteIcons.NAMES[current]}", VoteIcons.of(current), JBLabel.LEADING).apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
            add(error)
        }
        field.onSubmit = { submit(if (canVote) approve else null) }
        field.onCancel = { popup?.cancel() }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .also {
                if (above) it.show(PopupShowOptions.aboveComponent(anchor)) else it.showUnderneathOf(anchor)
                field.focus()
            }
    }

    /** Button text per choice; null sends comments only. */
    private val VOTE_ACTIONS = mapOf(
        Vote.APPROVED to "Approve",
        Vote.APPROVED_WITH_SUGGESTIONS to "Approve with Suggestions",
        Vote.REJECTED to "Reject",
        Vote.WAITING_FOR_AUTHOR to "Request Changes",
        null to "Comment",
    )
}
