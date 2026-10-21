package io.github.antomys.azdoprreview.ui

import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.View

/** Read-only Markdown text that wraps to the width its container gives it; [color] overrides the text color. */
class MarkdownPane(markdown: String, color: Color? = null) : JEditorPane() {
    init {
        color?.let { foreground = it }
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.label()
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        // Setting the text moves the caret, which would scroll the enclosing panel to this pane.
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        text = convertMarkdownToHtml(markdown)
    }

    /** Height for the current width, so the text wraps instead of widening its container. */
    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        if (width <= 0) return natural
        val root = getUI().getRootView(this)
        val textWidth = width - insets.left - insets.right
        root.setSize(textWidth.toFloat(), 0f)
        val height = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        return Dimension(minOf(natural.width, width), height)
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val widthChanged = width != this.width
        super.setBounds(x, y, width, height)
        if (widthChanged) SwingUtilities.invokeLater { revalidate() }
    }
}
