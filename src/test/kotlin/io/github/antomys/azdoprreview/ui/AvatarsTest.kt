package io.github.antomys.azdoprreview.ui

import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.ReviewProvider
import io.github.antomys.azdoprreview.review.ReviewUser
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AvatarsTest {
    private val user = ReviewUser("u", "User")

    /** A provider whose picture download finishes only when the test completes [picture]. */
    private class GatedProvider(val picture: CompletableDeferred<ByteArray?>) : ReviewProvider by FakeReviewProvider() {
        override suspend fun avatar(user: ReviewUser) = picture.await()
    }

    /** A component without a peer; [repainted] records the repaint requested when the picture arrives. */
    private class Target : Component() {
        @Volatile
        var repainted = false

        override fun repaint() {
            repainted = true
        }
    }

    @Test
    fun `a failed or empty load stops registering components`() {
        val provider = GatedProvider(CompletableDeferred())
        val avatars = Avatars.of(provider)
        val first = Target()
        avatars.icon(user, 20, first)

        provider.picture.complete(null)
        await("waiting still holds the component after an empty load") { waiting(avatars)[user.id] == null }

        val second = Target()
        avatars.icon(user, 20, second)
        assertNull(waiting(avatars)[user.id])
    }

    @Test
    fun `a component registered before the picture arrives is repainted`() {
        val provider = GatedProvider(CompletableDeferred())
        val avatars = Avatars.of(provider)
        val target = Target()
        avatars.icon(user, 20, target)

        provider.picture.complete(png())
        await("the component was not repainted") { target.repainted }
        assertTrue(images(avatars).containsKey(user.id))
        assertNull(waiting(avatars)[user.id])
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun waiting(avatars: Avatars) = field(avatars, "waiting") as Map<String, Set<Component>>

    @Suppress("UNCHECKED_CAST")
    private fun images(avatars: Avatars) = field(avatars, "images") as Map<String, BufferedImage>

    private fun field(avatars: Avatars, name: String): Any =
        Avatars::class.java.getDeclaredField(name).apply { isAccessible = true }.get(avatars)

    companion object {
        @JvmStatic
        @BeforeAll
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }
    }
}
