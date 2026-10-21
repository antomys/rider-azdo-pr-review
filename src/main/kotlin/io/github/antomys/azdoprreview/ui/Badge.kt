package io.github.antomys.azdoprreview.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/** Small upper-case tag on a filled, rounded background, e.g. "DRAFT" or "PENDING". */
class Badge(text: String) : JBLabel(text) {
    init {
        font = JBFont.small().asBold()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(1, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = BACKGROUND
            val arc = JBUI.scale(6)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private companion object {
        val BACKGROUND = JBColor(0xE6E8EB, 0x3C3F44)
    }
}
