package io.github.antomys.azdoprreview.ui

import com.intellij.markdown.utils.convertMarkdownToHtml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader
import javax.swing.JLabel
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

class MarkdownHtmlTest {
    private fun render(markdown: String) = sanitizeHtml(convertMarkdownToHtml(markdown))

    /** The tags Swing's own HTML parser (the one behind the editor kit) sees in [html]. */
    private fun parsedTags(html: String): List<String> {
        val tags = mutableListOf<String>()
        ParserDelegator().parse(StringReader(html), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) { tags += t.toString() }
            override fun handleSimpleTag(t: HTML.Tag, a: MutableAttributeSet, pos: Int) { tags += t.toString() }
        }, true)
        return tags
    }

    @Test
    fun `raw html tags are dropped or shown as text`() {
        val html = render(
            """Hi <img src="http://evil.example/t.png"> <a href="file:///etc/hosts">x</a> [js](javascript:alert(1)) """ +
                """<form action="http://evil.example/f"><input type="submit"></form> <script>alert(1)</script>""",
        )

        for (needle in listOf("<img", "<form", "<script", "file:")) assertFalse(html.contains(needle), "$needle in $html")
        assertTrue(html.contains("&lt;form action=\"http://evil.example/f\"&gt;"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html)
        assertTrue(html.contains("<a>x</a>"), html)
        assertTrue(html.contains("<a>js</a>"), html)
    }

    @Test
    fun `ordinary markdown still renders`() {
        val html = render("**bold** `code` [link](https://x.example) and a list item\n\n- item")

        for (needle in listOf("<strong>bold</strong>", "<code>code</code>", "<a href=\"https://x.example\">link</a>", "<li>item</li>")) {
            assertTrue(html.contains(needle), "$needle missing from $html")
        }
    }

    @Test
    fun `headings, task lists, strikethrough, fenced code and tables keep their tags`() {
        val html = render("# Title\n\n~~gone~~\n\n- [x] done\n\n```kotlin\nval a = 1\n```\n\n| a |\n|---|\n| 1 |")

        for (needle in listOf("<h1>Title</h1>", "<span>gone</span>", "<input type=\"checkbox\" checked disabled>", "<pre><code>", "<th>a</th>", "<td>1</td>")) {
            assertTrue(html.contains(needle), "$needle missing from $html")
        }
        assertFalse(html.contains("&lt;"), html)
    }

    @Test
    fun `mailto links stay, javascript and other schemes are dropped`() {
        assertTrue(render("[mail](mailto:a@x.example)").contains("<a href=\"mailto:a@x.example\">"))
        assertEquals("<p><a>x</a></p>", render("""<a href="javascript:alert(1)">x</a>""").trim())
        assertEquals("<p><a>x</a></p>", render("""<a href="JAVASCRIPT:alert(1)" onclick="x">x</a>""").trim())
        assertEquals("<p><a href=\"HTTPS://x.example\">x</a></p>", render("""<a href="HTTPS://x.example" target="_blank">x</a>""").trim())
    }

    @Test
    fun `unterminated tag at the end of an html block is not parsed as an image`() {
        for (markdown in listOf("<div>\n<img src=\"http://evil.example/b\"", "<div>\n<img src=\"http://evil.example/b\"\n", "<div>\n<img src=\"http://evil.example/b\"\nmore")) {
            val html = render(markdown)

            assertFalse(html.contains("<img"), html)
            assertFalse(parsedTags(html).contains("img"), html)
        }
    }

    @Test
    fun `font keeps a colour and nothing else`() {
        assertEquals("<p><strong>t</strong> <font color=\"#888888\">#1</font></p>", render("**t** <font color=\"#888888\">#1</font>").trim())
        assertEquals("<p><font>x</font></p>", render("<font face=\"x\" onclick=\"y\">x</font>").trim())
        assertEquals("<p><font>x</font></p>", render("<font color=\"url(x)\">x</font>").trim())
    }

    @Test
    fun `plain text label does not render a leading html tag`() {
        System.setProperty("java.awt.headless", "true")

        val label = JLabel("<html><img src=\"http://evil.example/b\">").plainText()

        assertEquals(true, label.getClientProperty("html.disable"))
        assertNull(label.getClientProperty(BasicHTML.propertyKey))
        // Without the switch the same text gets an HTML view, so the assertion above is meaningful.
        assertNotNull(JLabel("<html><b>x</b>").getClientProperty(BasicHTML.propertyKey))
    }
}
