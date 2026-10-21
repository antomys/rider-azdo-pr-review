package io.github.antomys.azdoprreview.ai

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor

/** A changed file's bytes on each side, as fetched for a review; null where the file doesn't exist. */
class FileContents(val change: FileChange, val old: ByteArray?, val new: ByteArray?)

/** A changed file that isn't sent, and why. */
data class ExcludedFile(val path: String, val reason: String)

/** A finding placed on its file and lines in the diff, ready to become a draft or a thread. */
data class ReviewFinding(val anchor: ThreadAnchor, val severity: Severity, val title: String, val body: String) {
    /** The comment text: the title in bold, then the body. */
    val comment: String get() = "**$title**\n\n$body"
}

/** Findings that fit the diff; the others are counted, by reason, so the user can see that some were dropped. */
data class ValidatedFindings(val findings: List<ReviewFinding>, val outsideDiff: Int, val overLimit: Int) {
    val dropped: Int get() = outsideDiff + overLimit
}

/** Builds what is sent to an [AiReviewer] and checks what comes back. Pure functions, no I/O. */
object AiReviewRequests {
    const val DEFAULT_MAX_BYTES = 200_000
    const val DEFAULT_MAX_FINDINGS = 20
    private const val CONTEXT_LINES = 3

    /** Secret-like files that are never sent unless the user removes the glob. */
    val DEFAULT_EXCLUDES = listOf(
        ".env", ".env.*", "*.pem", "*.key", "*.pfx", "*.p12", "id_rsa*", "appsettings.*.json", "secrets.*", "*.keystore",
    )

    /** Globs separated by commas or new lines. */
    fun parseGlobs(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The first glob that matches [path], or null. A glob without `/` matches the file name, one with `/` the whole path
     * (no leading `/`). `*` and `?` stay within a path segment, `**` crosses segments. Case is ignored, so `.ENV` is
     * excluded as well.
     */
    fun matchingGlob(path: String, globs: List<String>): String? {
        val relative = path.removePrefix("/")
        val name = relative.substringAfterLast('/')
        return globs.firstOrNull { glob -> globRegex(glob).matches(if ('/' in glob) relative else name) }
    }

    private fun globRegex(glob: String): Regex {
        val pattern = StringBuilder()
        var i = 0
        val text = glob.removePrefix("/")
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**/", i) -> { pattern.append("(?:.*/)?"); i += 2 }
                text.startsWith("**", i) -> { pattern.append(".*"); i++ }
                c == '*' -> pattern.append("[^/]*")
                c == '?' -> pattern.append("[^/]")
                else -> pattern.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(pattern.toString(), RegexOption.IGNORE_CASE)
    }

    /** How a path is shown to the tool: without the leading `/`. */
    fun displayPath(path: String): String = path.removePrefix("/")

    /**
     * Diffs [contents] in order. Binary files, files without text changes and files that would take the total past
     * [maxBytes] are left out, with the reason.
     */
    fun diffFiles(contents: List<FileContents>, maxBytes: Int = DEFAULT_MAX_BYTES): Pair<List<FileDiff>, List<ExcludedFile>> {
        val sent = mutableListOf<FileDiff>()
        val excluded = mutableListOf<ExcludedFile>()
        var total = 0
        for (file in contents) {
            val path = file.change.newPath ?: file.change.oldPath.orEmpty()
            if (file.old.isBinary() || file.new.isBinary()) {
                excluded += ExcludedFile(path, "binary file")
                continue
            }
            val old = lines(file.old)
            val new = lines(file.new)
            val diff = try {
                unifiedDiff(file.change.oldPath, file.change.newPath, old, new)
            } catch (e: FilesTooBigForDiffException) {
                excluded += ExcludedFile(path, "too large to diff")
                continue
            }
            if (diff.isEmpty()) {
                excluded += ExcludedFile(path, "no text changes")
                continue
            }
            val fileDiff = FileDiff(file.change, diff, old.size, new.size)
            if (total + fileDiff.bytes > maxBytes) {
                excluded += ExcludedFile(path, "over the ${StringUtil.formatFileSize(maxBytes.toLong())} limit")
                continue
            }
            total += fileDiff.bytes
            sent += fileDiff
        }
        return sent to excluded
    }

    /** The file's lines without line breaks; a final line break doesn't start another line. */
    fun lines(bytes: ByteArray?): List<String> {
        val text = bytes?.decodeToString()?.removePrefix("﻿") ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }
    }

    /** A unified diff with [CONTEXT_LINES] lines of context, or "" when the lines are equal. */
    fun unifiedDiff(oldPath: String?, newPath: String?, old: List<String>, new: List<String>): String {
        val changes = Diff.buildChanges(old.toTypedArray(), new.toTypedArray())?.toList() ?: return ""
        val out = StringBuilder()
        out.append("--- ").append(oldPath?.let { "a/" + displayPath(it) } ?: "/dev/null").append('\n')
        out.append("+++ ").append(newPath?.let { "b/" + displayPath(it) } ?: "/dev/null").append('\n')
        // Changes closer than twice the context share a hunk, as in `diff -u`.
        val hunks = mutableListOf(mutableListOf(changes.first()))
        for (change in changes.drop(1)) {
            val previous = hunks.last().last()
            if (change.line0 - (previous.line0 + previous.deleted) <= 2 * CONTEXT_LINES) hunks.last() += change else hunks += mutableListOf(change)
        }
        for (hunk in hunks) {
            val first = hunk.first()
            val last = hunk.last()
            val before = minOf(CONTEXT_LINES, first.line0)
            val after = minOf(CONTEXT_LINES, old.size - (last.line0 + last.deleted))
            val oldStart = first.line0 - before
            val newStart = first.line1 - before
            val oldCount = last.line0 + last.deleted + after - oldStart
            val newCount = last.line1 + last.inserted + after - newStart
            out.append("@@ -").append(range(oldStart, oldCount)).append(" +").append(range(newStart, newCount)).append(" @@\n")
            var i = oldStart
            for (change in hunk) {
                while (i < change.line0) out.append(' ').append(old[i++]).append('\n')
                for (k in change.line0 until change.line0 + change.deleted) out.append('-').append(old[k]).append('\n')
                for (k in change.line1 until change.line1 + change.inserted) out.append('+').append(new[k]).append('\n')
                i = change.line0 + change.deleted
            }
            while (i < oldStart + oldCount) out.append(' ').append(old[i++]).append('\n')
        }
        return out.toString()
    }

    // `diff -u` writes an empty range as the line before it.
    private fun range(start: Int, count: Int) = if (count == 0) "$start,0" else "${start + 1},$count"

    private fun ByteArray?.isBinary() = this != null && 0.toByte() in this

    /**
     * Keeps the findings whose path is one of [files] and whose lines exist on that side of the file, up to
     * [maxFindings]. "old" lines go on the base side, so only files that existed before can take them.
     */
    fun validate(findings: List<AiFinding>, files: List<FileDiff>, maxFindings: Int): ValidatedFindings {
        val valid = findings.mapNotNull { finding -> place(finding, files) }
        val outside = findings.size - valid.size
        return ValidatedFindings(valid.take(maxFindings), outside, (valid.size - maxFindings).coerceAtLeast(0))
    }

    private fun place(finding: AiFinding, files: List<FileDiff>): ReviewFinding? {
        val file = findFile(finding.path.trim(), files) ?: return null
        val (path, lineCount) = when (finding.side) {
            Side.LEFT -> file.change.oldPath to file.oldLines
            Side.RIGHT -> file.change.newPath to file.newLines
        }
        val start = minOf(finding.startLine, finding.endLine)
        val end = maxOf(finding.startLine, finding.endLine)
        if (path == null || start < 1 || end > lineCount) return null
        return ReviewFinding(ThreadAnchor(path, LineRange(finding.side, start, end)), finding.severity, finding.title.trim(), finding.body.trim())
    }

    /** The file [path] names, as written in the diff headers, with or without the `a/` or `b/` prefix or a leading `/`. */
    private fun findFile(path: String, files: List<FileDiff>): FileDiff? {
        fun lookup(p: String) = files.firstOrNull { f -> listOfNotNull(f.change.oldPath, f.change.newPath).any { displayPath(it) == p } }
        val relative = displayPath(path)
        return lookup(relative)
            ?: relative.takeIf { it.startsWith("a/") || it.startsWith("b/") }?.let { lookup(it.substring(2)) }
    }
}
