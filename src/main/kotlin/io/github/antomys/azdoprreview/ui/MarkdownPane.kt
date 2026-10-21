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
        text = sanitizeHtml(convertMarkdownToHtml(markdown))
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

/**
 * What the converter emits for Markdown syntax (`span` carries strikethrough) plus `font`, which the panels use for
 * the grey `#id` next to a title; any other tag is shown as text.
 */
private val ALLOWED_TAGS = setOf(
    "p", "br", "strong", "em", "b", "i", "s", "del", "span", "font", "code", "pre", "ul", "ol", "li", "blockquote",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "a", "table", "thead", "tbody", "tr", "th", "td", "input",
)
// Swing's parser also accepts end of input as the end of a tag, so an unterminated tag must be caught too.
private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)(?:>|\z)""")
private val HREF = Regex("""\bhref\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
private val SAFE_HREF = Regex("""^(https?://|mailto:)""", RegexOption.IGNORE_CASE)
private val INPUT_ATTR = Regex("""\b(type|checked|disabled)\b(\s*=\s*"[^"]*")?""", RegexOption.IGNORE_CASE)
private val FONT_COLOR = Regex("""\bcolor\s*=\s*"(#[0-9a-fA-F]{3,6}|[a-zA-Z]+)"""", RegexOption.IGNORE_CASE)

/**
 * Keeps only the tags and attributes Markdown needs, so raw HTML in a description or comment can't make the IDE
 * fetch remote images or open odd links; images are dropped and any other tag shows as the text the author wrote.
 * The converter passes raw HTML through unchanged, and the editor kit only knows HTML 3.2, so a tag regex is enough.
 */
fun sanitizeHtml(html: String): String = TAG.replace(html) { match ->
    val (slash, name, attrs) = match.destructured
    val tag = name.lowercase()
    when {
        tag == "img" -> ""
        tag !in ALLOWED_TAGS -> match.value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        slash.isNotEmpty() -> "</$tag>"
        tag == "a" -> HREF.find(attrs)?.groupValues?.get(1)?.trim()?.takeIf { SAFE_HREF.containsMatchIn(it) }
            ?.let { "<a href=\"$it\">" } ?: "<a>"
        tag == "input" -> "<input" + INPUT_ATTR.findAll(attrs).joinToString("") { " ${it.value}" } + ">"
        tag == "font" -> FONT_COLOR.find(attrs)?.let { "<font color=\"${it.groupValues[1]}\">" } ?: "<font>"
        else -> "<$tag>"
    }
}
