package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Hand-written samples of `claude -p --output-format json`; the real envelope shape is UNVERIFIED. */
class ClaudeOutputTest {
    private val finding = """{"path":"src/a.cs","side":"new","startLine":3,"endLine":4,"severity":"error","title":"Null check","body":"`x` can be **null**."}"""
    private val expected = AiFinding("src/a.cs", Side.RIGHT, 3, 4, Severity.ERROR, "Null check", "`x` can be **null**.")

    @Test
    fun `reads the structured output`() {
        val stdout = """{"type":"result","subtype":"success","is_error":false,"duration_ms":1200,"result":"Done.",""" +
            """"session_id":"00000000-0000-0000-0000-000000000000","total_cost_usd":0.01,"structured_output":{"findings":[$finding]}}"""
        assertEquals(listOf(expected), ClaudeOutput.parse(stdout).findings)
    }

    @Test
    fun `falls back to the result text, fenced or with text around it`() {
        fun envelope(result: String) = """{"type":"result","is_error":false,"result":${kotlinx.serialization.json.JsonPrimitive(result)}}"""
        assertEquals(listOf(expected), ClaudeOutput.parse(envelope("""{"findings":[$finding]}""")).findings)
        assertEquals(listOf(expected), ClaudeOutput.parse(envelope("```json\n{\"findings\":[$finding]}\n```")).findings)
        assertEquals(listOf(expected), ClaudeOutput.parse(envelope("Here you go:\n{\"findings\":[$finding]}\nThanks")).findings)
    }

    @Test
    fun `takes the result message from a list of messages`() {
        val stdout = """[{"type":"system","subtype":"init"},{"type":"assistant","message":{}},""" +
            """{"type":"result","is_error":false,"structured_output":{"findings":[$finding]}}]"""
        assertEquals(listOf(expected), ClaudeOutput.parse(stdout).findings)
    }

    @Test
    fun `defaults what it can and skips findings without a path or line`() {
        val stdout = """{"type":"result","is_error":false,"structured_output":{"findings":[""" +
            """{"path":"a.cs","startLine":2,"severity":"critical","title":"T"},{"path":"","startLine":1},{"path":"b.cs"},""" +
            """{"path":"c.cs","side":"OLD","startLine":"7","endLine":8,"severity":"Info","body":"B"},"text"]}}"""
        val result = ClaudeOutput.parse(stdout)
        assertEquals(
            listOf(
                AiFinding("a.cs", Side.RIGHT, 2, 2, Severity.WARNING, "T", ""),
                AiFinding("c.cs", Side.LEFT, 7, 8, Severity.INFO, "", "B"),
            ),
            result.findings,
        )
        assertEquals(3, result.skipped)
    }

    @Test
    fun `reports an error envelope, with a sign-in hint when it is about logging in`() {
        val e = assertThrows<AiReviewException> {
            ClaudeOutput.parse("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}""")
        }
        assertTrue(e.message!!.contains("Invalid API key"), e.message)
        assertTrue(e.message!!.contains("sign in"), e.message)

        val other = assertThrows<AiReviewException> { ClaudeOutput.parse("""{"type":"result","subtype":"error_max_turns","is_error":true}""") }
        assertEquals("Claude Code failed: error_max_turns.", other.message)
    }

    @Test
    fun `rejects output that isn't JSON or has no findings`() {
        assertThrows<AiReviewException> { ClaudeOutput.parse("Error: something broke") }
        assertThrows<AiReviewException> { ClaudeOutput.parse("""{"type":"result","is_error":false,"result":"I found no issues."}""") }
        assertThrows<AiReviewException> { ClaudeOutput.parse("""{"type":"result","is_error":false,"structured_output":{"items":[]}}""") }
        assertEquals(emptyList<AiFinding>(), ClaudeOutput.parse("""{"type":"result","structured_output":{"findings":[]}}""").findings)
    }
}
