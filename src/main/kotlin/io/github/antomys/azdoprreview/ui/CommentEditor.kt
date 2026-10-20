package io.github.antomys.azdoprreview.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A text box with a submit button. [submit] runs in [scope]; on failure the text stays and the error is shown below.
 * ⌘/Ctrl+Enter submits, Escape cancels.
 */
class CommentEditor(
    private val scope: CoroutineScope,
    submitText: String,
    placeholder: String,
    private val submit: suspend (String) -> Unit,
    private val cancel: (() -> Unit)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val text = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = placeholder
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(4, 6))
    }
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
        add(text, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        text.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && (e.isMetaDown || e.isControlDown) -> send().also { e.consume() }
                    e.keyCode == KeyEvent.VK_ESCAPE && cancel != null -> cancel.invoke().also { e.consume() }
                }
            }
        })
    }

    fun focus() {
        text.requestFocusInWindow()
    }

    private fun send() {
        val body = text.text.trim()
        if (body.isEmpty() || !submitButton.isEnabled) return
        setBusy(true)
        scope.launch(Dispatchers.EDT) {
            try {
                submit(body)
                text.text = ""
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
        text.isEnabled = !busy
    }
}
