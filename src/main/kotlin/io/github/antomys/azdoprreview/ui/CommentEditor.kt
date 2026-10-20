package io.github.antomys.azdoprreview.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A [CommentField] with a submit button. [submit] runs in [scope]; on failure the text stays and the error is shown below.
 * ⌘/Ctrl+Enter submits, Escape cancels.
 */
class CommentEditor(
    project: Project,
    private val scope: CoroutineScope,
    submitText: String,
    placeholder: String,
    mentions: Mentions,
    private val submit: suspend (String) -> Unit,
    private val cancel: (() -> Unit)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val field = CommentField(project, scope, placeholder, mentions)
    private val error = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        isVisible = false
    }
    private val submitButton = JButton(submitText).apply { addActionListener { send() } }

    init {
        isOpaque = false
        val shortcut = if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"
        submitButton.toolTipText = "$submitText ($shortcut)"
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(submitButton)
            cancel?.let { action -> add(JButton("Cancel").apply { addActionListener { action() } }) }
            add(error)
        }
        field.onSubmit = ::send
        field.onCancel = cancel
        add(field, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    fun focus() = field.focus()

    private fun send() {
        val body = field.text
        if (body.isEmpty() || !submitButton.isEnabled) return
        setBusy(true)
        scope.launch(Dispatchers.EDT) {
            try {
                submit(body)
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
        submitButton.isEnabled = !busy
        field.isEnabled = !busy
    }
}
