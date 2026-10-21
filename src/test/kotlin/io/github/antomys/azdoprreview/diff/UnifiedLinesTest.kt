package io.github.antomys.azdoprreview.diff

import io.github.antomys.azdoprreview.diff.UnifiedLines.SideLine
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.ReviewComment
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class UnifiedLinesTest {
    /**
     * A unified view of a 4-line base and a 4-line iteration (0-based lines):
     * ```
     * 0  L0 R0  unchanged
     * 1  L1 --  deleted
     * 2  L2 --  deleted
     * 3  -- R1  inserted
     * 4  L3 R2  unchanged
     * 5  -- R3  inserted
     * ```
     */
    private val rows = listOf(0 to 0, 1 to null, 2 to null, null to 1, 3 to 2, null to 3)

    private val lines = UnifiedLines(object : UnifiedLines.Converter {
        override fun toUnified(side: Side, line: Int) = rows.indexOfFirst { side.of(it) == line }
        override fun fromUnified(side: Side, line: Int) = rows.getOrNull(line)?.let { side.of(it) } ?: -1
        override fun lineCount(side: Side) = rows.count { side.of(it) != null }
    })

    private fun Side.of(row: Pair<Int?, Int?>) = if (this == Side.LEFT) row.first else row.second

    @Test
    fun `side lines map to the unified line that shows them`() {
        assertEquals(0, lines.toUnified(Side.LEFT, 0))
        assertEquals(0, lines.toUnified(Side.RIGHT, 0))
        assertEquals(2, lines.toUnified(Side.LEFT, 2))
        assertEquals(3, lines.toUnified(Side.RIGHT, 1))
        assertEquals(4, lines.toUnified(Side.LEFT, 3))
        assertEquals(4, lines.toUnified(Side.RIGHT, 2))
    }

    @Test
    fun `lines past either end of a side are clamped to it`() {
        assertEquals(4, lines.toUnified(Side.LEFT, 9))
        assertEquals(5, lines.toUnified(Side.RIGHT, 9))
        assertEquals(0, lines.toUnified(Side.LEFT, -1))
    }

    @Test
    fun `before the diff is computed nothing maps`() {
        val pending = UnifiedLines(object : UnifiedLines.Converter {
            override fun toUnified(side: Side, line: Int) = -1
            override fun fromUnified(side: Side, line: Int) = -1
            override fun lineCount(side: Side) = 4
        })
        assertEquals(-1, pending.toUnified(Side.RIGHT, 1))
        assertNull(pending.sideAt(1))
    }

    @Test
    fun `a deleted line is commented on the left side, inserted and unchanged lines on the right`() {
        assertEquals(SideLine(Side.RIGHT, 0), lines.sideAt(0))
        assertEquals(SideLine(Side.LEFT, 1), lines.sideAt(1))
        assertEquals(SideLine(Side.RIGHT, 1), lines.sideAt(3))
        assertEquals(SideLine(Side.RIGHT, 2), lines.sideAt(4))
        assertNull(lines.sideAt(6))
    }

    @Test
    fun `a selection covers the lines the clicked side has`() {
        // Unified lines 1..4: deleted L1, L2, inserted R1, unchanged L3/R2.
        assertEquals(1..2, lines.commentRange(Side.RIGHT, 1, 4))
        assertEquals(1..3, lines.commentRange(Side.LEFT, 1, 4))
        assertEquals(1..1, lines.commentRange(Side.LEFT, 1, 1))
        assertNull(lines.commentRange(Side.RIGHT, 1, 2))
    }

    @Test
    fun `adjacent threads follow their unified position, file-level threads first`() {
        val fileLevel = thread(1, null)
        val deleted = thread(2, LineRange(Side.LEFT, 2, 2)) // unified 1
        val inserted = thread(3, LineRange(Side.RIGHT, 2, 2)) // unified 3
        val unchangedLeft = thread(4, LineRange(Side.LEFT, 4, 4)) // unified 4
        val threads = listOf(unchangedLeft, inserted, fileLevel, deleted)

        assertEquals(fileLevel, lines.adjacent(threads, 0, forward = false))
        assertEquals(deleted, lines.adjacent(threads, 0, forward = true))
        assertEquals(inserted, lines.adjacent(threads, 1, forward = true))
        assertEquals(unchangedLeft, lines.adjacent(threads, 3, forward = true))
        assertNull(lines.adjacent(threads, 4, forward = true))
        assertEquals(inserted, lines.adjacent(threads, 4, forward = false))
        assertEquals(deleted, lines.adjacent(threads, 2, forward = false))
    }

    private fun thread(id: Int, lines: LineRange?) = ReviewThread(
        id, ThreadAnchor("/a.cs", lines), null, false,
        listOf(ReviewComment(1, null, ReviewUser("u", "User"), "text", Instant.EPOCH, false)),
    )
}
