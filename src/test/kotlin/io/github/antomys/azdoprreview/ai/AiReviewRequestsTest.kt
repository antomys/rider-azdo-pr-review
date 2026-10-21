package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiReviewRequestsTest {
    private val defaults = AiReviewRequests.DEFAULT_EXCLUDES

    @Test
    fun `default excludes match secret-like files by name, ignoring case`() {
        val excluded = listOf(
            "/.env", "/src/.env.local", "/certs/server.pem", "/a/b/tls.KEY", "/cert.pfx", "/cert.p12", "/home/id_rsa",
            "/home/id_rsa.pub", "/src/Api/appsettings.Production.json", "/config/secrets.yaml", "/android/release.keystore",
        )
        excluded.forEach { assertTrue(AiReviewRequests.matchingGlob(it, defaults) != null, it) }
        listOf("/src/Api/appsettings.json", "/src/environment.cs", "/docs/keys.md", "/src/Secrets/Vault.cs").forEach {
            assertNull(AiReviewRequests.matchingGlob(it, defaults), it)
        }
        assertEquals("appsettings.*.json", AiReviewRequests.matchingGlob("/src/appsettings.Development.json", defaults))
    }

    @Test
    fun `globs with a slash match the path, and double stars cross folders`() {
        val globs = AiReviewRequests.parseGlobs("docs/*.md,\n  src/**/generated/*  \n\n")
        assertEquals(listOf("docs/*.md", "src/**/generated/*"), globs)
        assertEquals("docs/*.md", AiReviewRequests.matchingGlob("/docs/notes.md", globs))
        assertNull(AiReviewRequests.matchingGlob("/docs/sub/notes.md", globs))
        assertEquals("src/**/generated/*", AiReviewRequests.matchingGlob("/src/a/b/generated/Api.cs", globs))
        assertEquals("src/**/generated/*", AiReviewRequests.matchingGlob("/src/generated/Api.cs", globs))
        assertNull(AiReviewRequests.matchingGlob("/notes.md", globs))
    }

    @Test
    fun `builds a unified diff with three lines of context`() {
        val old = (1..12).map { "line $it" }
        val new = old.toMutableList().apply { set(4, "line five"); add(10, "inserted") }
        val diff = AiReviewRequests.unifiedDiff("/src/a.txt", "/src/a.txt", old, new)
        assertEquals(
            """
            --- a/src/a.txt
            +++ b/src/a.txt
            @@ -2,11 +2,12 @@
             line 2
             line 3
             line 4
            -line 5
            +line five
             line 6
             line 7
             line 8
             line 9
             line 10
            +inserted
             line 11
             line 12

            """.trimIndent(),
            diff,
        )
    }

    @Test
    fun `separates distant changes into hunks and handles added and deleted files`() {
        val old = (1..20).map { "l$it" }
        val new = old.toMutableList().apply { set(0, "first"); set(19, "last") }
        val hunks = AiReviewRequests.unifiedDiff("/f", "/f", old, new).lines().filter { it.startsWith("@@") }
        assertEquals(listOf("@@ -1,4 +1,4 @@", "@@ -17,4 +17,4 @@"), hunks)

        assertEquals("--- /dev/null\n+++ b/new.cs\n@@ -0,0 +1,2 @@\n+a\n+b\n", AiReviewRequests.unifiedDiff(null, "/new.cs", emptyList(), listOf("a", "b")))
        assertEquals("--- a/old.cs\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-a\n", AiReviewRequests.unifiedDiff("/old.cs", null, listOf("a"), emptyList()))
        assertEquals("", AiReviewRequests.unifiedDiff("/same", "/same", listOf("a"), listOf("a")))
    }

    @Test
    fun `splits lines without a phantom last line and without carriage returns`() {
        assertEquals(listOf("a", "b"), AiReviewRequests.lines("a\r\nb\r\n".toByteArray()))
        assertEquals(listOf("a", ""), AiReviewRequests.lines("a\n\n".toByteArray()))
        assertEquals(emptyList<String>(), AiReviewRequests.lines("".toByteArray()))
        assertEquals(emptyList<String>(), AiReviewRequests.lines(null))
    }

    @Test
    fun `leaves out binary files, files without text changes and files past the size cap`() {
        val text = FileContents(change("/a.cs"), "x\n".toByteArray(), "y\n".toByteArray())
        val binary = FileContents(change("/logo.png"), null, byteArrayOf(1, 0, 2))
        val renamedOnly = FileContents(FileChange("/old.cs", "/new.cs", ChangeKind.RENAMED), "same".toByteArray(), "same".toByteArray())
        val big = FileContents(change("/big.cs"), null, "z".repeat(500).toByteArray())
        val small = FileContents(change("/small.cs"), null, "s".toByteArray())

        val (sent, excluded) = AiReviewRequests.diffFiles(listOf(text, binary, renamedOnly, big, small), maxBytes = 200)

        assertEquals(listOf("/a.cs", "/small.cs"), sent.map { it.change.newPath })
        assertEquals(
            listOf(ExcludedFile("/logo.png", "binary file"), ExcludedFile("/new.cs", "no text changes"), ExcludedFile("/big.cs", "over the 200 B limit")),
            excluded,
        )
        assertEquals(1, sent[0].oldLines)
        assertEquals(1, sent[0].newLines)
    }

    @Test
    fun `keeps findings on lines of the sent files and counts the rest`() {
        val modified = FileDiff(change("/src/a.cs"), "diff", oldLines = 10, newLines = 12)
        val deleted = FileDiff(FileChange("/src/gone.cs", null, ChangeKind.DELETED), "diff", oldLines = 5, newLines = 0)
        val renamed = FileDiff(FileChange("/src/old.cs", "/src/new.cs", ChangeKind.RENAMED), "diff", oldLines = 3, newLines = 4)
        val files = listOf(modified, deleted, renamed)
        fun finding(path: String, side: Side, start: Int, end: Int = start) = AiFinding(path, side, start, end, Severity.WARNING, " T ", " B ")

        val result = AiReviewRequests.validate(
            listOf(
                finding("src/a.cs", Side.RIGHT, 12),
                finding("/src/a.cs", Side.RIGHT, 13), // past the end
                finding("b/src/a.cs", Side.LEFT, 4, 2), // reversed range, base side
                finding("src/gone.cs", Side.LEFT, 5),
                finding("src/gone.cs", Side.RIGHT, 1), // no new side
                finding("src/new.cs", Side.LEFT, 3), // base side of a rename uses the old path
                finding("src/other.cs", Side.RIGHT, 1), // not sent
                finding("src/a.cs", Side.RIGHT, 0),
            ),
            files,
            maxFindings = 3,
        )

        assertEquals(
            listOf(
                ReviewFinding(ThreadAnchor("/src/a.cs", LineRange(Side.RIGHT, 12, 12)), Severity.WARNING, "T", "B"),
                ReviewFinding(ThreadAnchor("/src/a.cs", LineRange(Side.LEFT, 2, 4)), Severity.WARNING, "T", "B"),
                ReviewFinding(ThreadAnchor("/src/gone.cs", LineRange(Side.LEFT, 5, 5)), Severity.WARNING, "T", "B"),
            ),
            result.findings,
        )
        assertEquals(4, result.outsideDiff)
        assertEquals(1, result.overLimit)
        assertEquals(5, result.dropped)
        assertEquals("**T**\n\nB", result.findings[0].comment)
    }

    private fun change(path: String) = FileChange(path, path, ChangeKind.MODIFIED)
}
