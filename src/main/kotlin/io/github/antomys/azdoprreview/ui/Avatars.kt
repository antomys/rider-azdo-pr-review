package io.github.antomys.azdoprreview.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.antomys.azdoprreview.review.ReviewProvider
import io.github.antomys.azdoprreview.review.ReviewUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Round user pictures, loaded once per user from the provider. Until a picture arrives (or when there is none) the icon
 * shows the user's initials; components that show an icon are repainted when its picture arrives.
 */
class Avatars private constructor(private val provider: ReviewProvider) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val done = ConcurrentHashMap.newKeySet<String>()
    private val images = ConcurrentHashMap<String, BufferedImage>()
    private val waiting = ConcurrentHashMap<String, MutableSet<Component>>()

    /** An icon [size] (unscaled) pixels wide; [repaint] is repainted when the picture arrives. */
    fun icon(user: ReviewUser, size: Int, repaint: Component? = null): Icon {
        load(user)
        // Only a load still in flight can repaint; registering after a failed load would retain the component forever.
        if (repaint != null && user.id !in done) {
            waiting.getOrPut(user.id) { ConcurrentHashMap.newKeySet() } += repaint
            // The load may have finished between the check and the add, leaving a set nobody drains.
            if (user.id in done) waiting.remove(user.id)
        }
        return AvatarIcon(user, size)
    }

    private fun load(user: ReviewUser) {
        if (!requested.add(user.id)) return
        scope.launch {
            val image = try {
                provider.avatar(user)?.let { ImageIO.read(ByteArrayInputStream(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Initials stay in place of a picture that can't be loaded.
                null
            }
            if (image != null) images[user.id] = image
            // Mark done before draining, so a component registered meanwhile is either drained here or never added.
            done += user.id
            val components = waiting.remove(user.id)
            if (image != null) components?.forEach { UIUtil.invokeLaterIfNeeded { it.repaint() } }
        }
    }

    private inner class AvatarIcon(private val user: ReviewUser, private val size: Int) : Icon {
        override fun getIconWidth() = JBUI.scale(size)
        override fun getIconHeight() = JBUI.scale(size)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                val d = iconWidth.toDouble()
                val circle = Ellipse2D.Double(x.toDouble(), y.toDouble(), d, d)
                val image = images[user.id]
                if (image != null) {
                    g2.clip(circle)
                    g2.drawImage(image, x, y, iconWidth, iconHeight, null)
                } else {
                    g2.color = COLORS[Math.floorMod(user.id.hashCode(), COLORS.size)]
                    g2.fill(circle)
                    g2.color = JBColor.WHITE
                    g2.font = g2.font.deriveFont(Font.BOLD, (iconWidth * 0.42f))
                    val text = initials(user.displayName)
                    val metrics = g2.fontMetrics
                    g2.drawString(text, x + (iconWidth - metrics.stringWidth(text)) / 2, y + (iconHeight - metrics.height) / 2 + metrics.ascent)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private val COLORS = listOf(
            JBColor(0x5B8DEF, 0x4A78C9), JBColor(0x3FA86A, 0x388E5C), JBColor(0xD98A2B, 0xB8741F),
            JBColor(0x9B6BD8, 0x8259B8), JBColor(0xD9534F, 0xB84542), JBColor(0x2EA3A3, 0x268A8A),
        )
        private val instances = WeakHashMap<ReviewProvider, Avatars>()

        /** One cache per provider, so each picture is downloaded once. */
        @Synchronized
        fun of(provider: ReviewProvider): Avatars = instances.getOrPut(provider) { Avatars(provider) }

        fun initials(name: String): String =
            name.split(' ', '.', '_', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    }
}
