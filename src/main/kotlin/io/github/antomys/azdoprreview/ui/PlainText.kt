package io.github.antomys.azdoprreview.ui

import javax.swing.JLabel
import javax.swing.plaf.basic.BasicHTML

/** Shows the label's text literally even when it starts with `<html>`; for text that comes from the server. */
fun <T : JLabel> T.plainText(): T = apply {
    putClientProperty("html.disable", true)
    // The UI parsed the constructor's text as HTML already; re-running the renderer applies the switch to it.
    BasicHTML.updateRenderer(this, text)
}
