package io.github.antomys.azdoprreview.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import io.github.antomys.azdoprreview.review.ReviewThread
import io.github.antomys.azdoprreview.review.Side
import com.intellij.diff.util.Side as EditorSide

/**
 * Maps 0-based lines between the two sides of a diff and the unified view, whose one editor shows deleted, inserted and
 * unchanged lines interleaved. The mapping itself comes from a [Converter], so the rules here are testable without a viewer.
 */
class UnifiedLines(private val converter: Converter) {
    /** The viewer's own line mapping; every line is 0-based. */
    interface Converter {
        /** The unified line that shows [line] of [side], or -1 if none does (or the diff isn't computed yet). */
        fun toUnified(side: Side, line: Int): Int

        /** The line of [side] shown at unified [line], or -1 if that side doesn't have it (an inserted or deleted line). */
        fun fromUnified(side: Side, line: Int): Int

        /** The number of lines of [side]'s document. */
        fun lineCount(side: Side): Int
    }

    /** A 0-based [line] of [side]. */
    data class SideLine(val side: Side, val line: Int)

    /** The unified line that shows [line] of [side], clamped to that side's lines; -1 while the diff isn't computed. */
    fun toUnified(side: Side, line: Int): Int =
        converter.toUnified(side, line.coerceIn(0, maxOf(converter.lineCount(side) - 1, 0)))

    /**
     * The side and line a comment on unified [line] is anchored to, as in Azure DevOps: a deleted line is on the left
     * (base) side, an inserted or unchanged line on the right. Null when the line maps to neither side.
     */
    fun sideAt(line: Int): SideLine? {
        val right = converter.fromUnified(Side.RIGHT, line)
        if (right >= 0) return SideLine(Side.RIGHT, right)
        val left = converter.fromUnified(Side.LEFT, line)
        return if (left >= 0) SideLine(Side.LEFT, left) else null
    }

    /**
     * The 0-based lines of [side] a comment on the selected unified lines [first]..[last] covers.
     * A selection can span deleted and inserted lines, but a comment has one side: it takes the side of the line whose
     * "+" was clicked and covers the selected lines that side has (from the first to the last of them).
     */
    fun commentRange(side: Side, first: Int, last: Int): IntRange? {
        val lines = (first..last).map { converter.fromUnified(side, it) }.filter { it >= 0 }
        return if (lines.isEmpty()) null else lines.min()..lines.max()
    }

    /**
     * Of [threads] (all on the shown file), the first one below unified [caret] line, or the last one above it when not
     * [forward], ordered by where they show. File-level threads count as above the first line.
     */
    fun adjacent(threads: List<ReviewThread>, caret: Int, forward: Boolean): ReviewThread? {
        val ordered = threads.map { position(it) to it }.sortedBy { it.first }
        return if (forward) ordered.firstOrNull { it.first > caret }?.second else ordered.lastOrNull { it.first < caret }?.second
    }

    /** The unified line a thread's first line shows at, or -1 for a file-level thread. */
    private fun position(thread: ReviewThread): Int = thread.anchor?.lines?.let { toUnified(it.side, it.start - 1) } ?: -1

    companion object {
        /** The mapping of [viewer]'s current diff; it follows rediffs, since it asks the viewer each time. */
        fun of(viewer: UnifiedDiffViewer) = UnifiedLines(object : Converter {
            override fun toUnified(side: Side, line: Int) = viewer.transferLineToOnesideStrict(side.editorSide(), line)
            override fun fromUnified(side: Side, line: Int) = viewer.transferLineFromOnesideStrict(side.editorSide(), line)
            override fun lineCount(side: Side) = viewer.getDocument(side.editorSide()).lineCount
        })

        private fun Side.editorSide() = if (this == Side.LEFT) EditorSide.LEFT else EditorSide.RIGHT
    }
}
