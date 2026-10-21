package io.github.antomys.azdoprreview.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/** What was written: [text] as typed, the people [mentioned] in it, and the text in the provider's mention format. */
class ComposedComment(val text: String, val mentioned: Map<String, ReviewUser>, val encoded: String)

/** A way to send a comment: [name] on the button, [hint] after the shortcut ("add draft"). */
class CommentAction(val name: String, val hint: String, val run: suspend (ComposedComment) -> Unit)

/**
 * The writer's avatar, a comment field, shortcut hints and a split button: [primary] runs from the button and ⌘/Ctrl+Enter,
 * [others] are in its drop-down. Escape (or Cancel) calls [cancel]. On failure the text stays and the error is shown.
 */
class CommentComposer(
    project: Project,
    private val scope: CoroutineScope,
    author: ReviewUser?,
    avatars: Avatars?,
    placeholder: String,
    mentions: Mentions,
    private val primary: CommentAction,
    private val others: List<CommentAction> = emptyList(),
    private val cancel: (() -> Unit)? = null,
    minLines: Int = 1,
) : JPanel(BorderLayout(JBUI.scale(10), 0)) {
    private val field = CommentField(project, scope, placeholder, mentions, minLines)
    private val error = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        isVisible = false
    }
    private val buttons = mutableListOf<javax.swing.JComponent>()

    init {
        isOpaque = false
        if (author != null && avatars != null) {
            add(JBLabel(avatars.icon(author, AVATAR_SIZE, this)).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
        }
        val submitKey = if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"
        val newLineKey = if (SystemInfo.isMac) "⏎" else "Enter"
        val hints = JBLabel("$submitKey to ${primary.hint}    $newLineKey to add new line").apply {
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
        }
        val send = if (others.isEmpty()) {
            JButton(primary.name).apply { addActionListener { send(primary) } }
        } else {
            JBOptionButton(action(primary), others.map(::action).toTypedArray<Action>())
        }
        buttons += send
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            cancel?.let { action -> add(JButton("Cancel").apply { addActionListener { action() } }.also(buttons::add)) }
            add(send)
        }
        field.onSubmit = { send(primary) }
        field.onCancel = cancel
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            // The hints shorten before the buttons would be cut off; errors get their own line.
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(hints, BorderLayout.CENTER)
                    add(right, BorderLayout.EAST)
                }, BorderLayout.CENTER)
                add(error, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    }

    fun focus() = field.focus()

    fun setText(text: String, mentioned: Map<String, ReviewUser>) = field.setText(text, mentioned)

    private fun action(action: CommentAction) = object : AbstractAction(action.name) {
        override fun actionPerformed(e: ActionEvent?) = send(action)
    }

    private fun send(action: CommentAction) {
        val comment = ComposedComment(field.displayText, field.mentioned, field.text)
        if (comment.text.isEmpty() || !field.isEnabled) return
        setBusy(true)
        scope.launch(Dispatchers.EDT) {
            try {
                action.run(comment)
                field.clear()
                error.isVisible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.text = e.message ?: e.javaClass.simpleName
                error.isVisible = true
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        field.isEnabled = !busy
        buttons.forEach { it.isEnabled = !busy }
    }

    private companion object {
        const val AVATAR_SIZE = 24
    }
}
