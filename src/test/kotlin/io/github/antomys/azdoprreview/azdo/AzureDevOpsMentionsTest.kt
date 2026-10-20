package io.github.antomys.azdoprreview.azdo

import io.github.antomys.azdoprreview.review.ReviewUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AzureDevOpsMentionsTest {
    private val known = "11111111-1111-1111-1111-111111111111"
    private val unknown = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `round-trips ids and renders known users`() {
        val text = "Please check ${AzureDevOpsMentions.encode(known)} and ${AzureDevOpsMentions.encode(unknown)}."

        assertEquals(setOf(known, unknown), AzureDevOpsMentions.ids(text))
        assertEquals(
            "Please check @Test User and @<$unknown>.",
            AzureDevOpsMentions.render(text, mapOf(known to ReviewUser(known, "Test User"))),
        )
    }

    @Test
    fun `ignores text that only looks like a mention`() {
        assertEquals(emptySet<String>(), AzureDevOpsMentions.ids("mail me @<not-a-guid> or a@b.c"))
    }
}
