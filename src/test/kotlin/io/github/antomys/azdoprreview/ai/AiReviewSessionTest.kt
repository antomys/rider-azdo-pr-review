package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FakeReviewProvider
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.LineRange
import io.github.antomys.azdoprreview.review.RepoRef
import io.github.antomys.azdoprreview.review.ReviewDetails
import io.github.antomys.azdoprreview.review.ReviewRef
import io.github.antomys.azdoprreview.review.ReviewState
import io.github.antomys.azdoprreview.review.ReviewSummary
import io.github.antomys.azdoprreview.review.ReviewTarget
import io.github.antomys.azdoprreview.review.ReviewUser
import io.github.antomys.azdoprreview.review.Revision
import io.github.antomys.azdoprreview.review.Side
import io.github.antomys.azdoprreview.review.ThreadAnchor
import io.github.antomys.azdoprreview.toolwindow.PullRequestDetailsViewModel
import io.github.antomys.azdoprreview.toolwindow.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** The whole flow without Swing: the fake provider serves the files, the fake reviewer answers. */
class AiReviewSessionTest {
    private val repo = RepoRef("project", "repo")
    private val summary = ReviewSummary(ReviewRef(repo, 7), "Add pricing", ReviewUser("u", "User"), "feature", "main", ReviewState.OPEN, false, Instant.EPOCH)
    private val revision = Revision(2, "src2", "target", "base")
    private val changes = listOf(
        FileChange("/src/Price.cs", "/src/Price.cs", ChangeKind.MODIFIED, "v1"),
        FileChange("/src/Legacy.cs", null, ChangeKind.DELETED, "v2"),
        FileChange("/src/Api/appsettings.Production.json", "/src/Api/appsettings.Production.json", ChangeKind.MODIFIED, "v3"),
        FileChange(null, "/src/Big.cs", ChangeKind.ADDED, "v4"),
        FileChange(null, "/docs/logo.png", ChangeKind.ADDED, "v5"),
    )
    private val files = mapOf(
        "base:/src/Price.cs" to "a\nb\nc\n", "src2:/src/Price.cs" to "a\nB\nc\nd\n",
        "base:/src/Legacy.cs" to "old 1\nold 2\n",
        "base:/src/Api/appsettings.Production.json" to "{}", "src2:/src/Api/appsettings.Production.json" to """{"key":"secret"}""",
        "src2:/src/Big.cs" to "x".repeat(10_000),
    ).mapValues { it.value.toByteArray() } + ("src2:/docs/logo.png" to byteArrayOf(-119, 80, 78, 71, 0, 1))

    private val provider = FakeReviewProvider(
        details = ReviewDetails(summary, "Adds discounts", emptyList()), revisions = listOf(revision), changes = mapOf(2 to changes), files = files,
    )

    private val findings = listOf(
        AiFinding("src/Price.cs", Side.RIGHT, 2, 2, Severity.ERROR, "Wrong case", "Use `b`."),
        AiFinding("src/Legacy.cs", Side.LEFT, 1, 2, Severity.INFO, "Still used?", "Check callers."),
        AiFinding("src/Price.cs", Side.RIGHT, 9, 9, Severity.WARNING, "Past the end", "-"),
        AiFinding("src/Api/appsettings.Production.json", Side.RIGHT, 1, 1, Severity.ERROR, "Not sent", "-"),
    )

    private suspend fun CoroutineScope.session(reviewer: AiReviewer = FakeAiReviewer(findings, skipped = 1)): Pair<PullRequestDetailsViewModel, AiReviewSession> {
        val pr = PullRequestDetailsViewModel(this, ReviewTarget(provider, repo), summary)
        pr.refresh().join()
        return pr to AiReviewSession(pr, reviewer, AiReviewOptions(maxBytes = 1000, instructions = "Be brief", maxFindings = 5))
    }

    @Test
    fun `prepares diffs of the sendable files and says why the others are left out`() = runBlocking {
        val (_, session) = session()

        val prepared = session.prepare()

        assertEquals(listOf("/src/Legacy.cs", "/src/Price.cs"), prepared.request.files.map { it.change.oldPath })
        assertEquals("--- a/src/Price.cs\n+++ b/src/Price.cs\n@@ -1,3 +1,4 @@\n a\n-b\n+B\n c\n+d\n", prepared.request.files[1].diff)
        assertEquals(
            listOf(
                ExcludedFile("/src/Api/appsettings.Production.json", "matches appsettings.*.json"),
                ExcludedFile("/docs/logo.png", "binary file"),
                ExcludedFile("/src/Big.cs", "over the 1 kB limit"),
            ),
            prepared.excluded,
        )
        assertEquals(AiReviewRequest("Add pricing", "Adds discounts", prepared.request.files, "Be brief", 5), prepared.request)
    }

    @Test
    fun `sends only the files the user kept, and drops findings outside them`() = runBlocking {
        val reviewer = FakeAiReviewer(findings, skipped = 1)
        val (_, session) = session(reviewer)
        val prepared = session.prepare()

        val result = session.review(prepared, leftOut = setOf("/src/Legacy.cs"))

        assertEquals(listOf("/src/Price.cs"), reviewer.requests.single().files.map { it.change.path })
        assertEquals(listOf("Wrong case"), result.findings.map { it.title })
        // Legacy.cs wasn't sent, line 9 is past the end, appsettings wasn't sent, and one item couldn't be read.
        assertEquals(4, result.outsideDiff)
    }

    @Test
    fun `adds the chosen findings as pending drafts on their lines`() = runBlocking {
        val (pr, session) = session()
        val result = session.review(session.prepare())

        session.addDrafts(result.findings)

        assertEquals(
            listOf(
                ThreadAnchor("/src/Price.cs", LineRange(Side.RIGHT, 2, 2)) to "**Wrong case**\n\nUse `b`.",
                ThreadAnchor("/src/Legacy.cs", LineRange(Side.LEFT, 1, 2)) to "**Still used?**\n\nCheck callers.",
            ),
            pr.drafts.value.map { it.anchor to it.body },
        )
        assertTrue(pr.drafts.value.all { it.replyTo == null })
        assertEquals(emptyList<Any>(), provider.createdThreads)
    }

    @Test
    fun `posts the chosen findings as threads now`() = runBlocking {
        val (pr, session) = session()
        val result = session.review(session.prepare())

        session.postNow(result.findings.take(1))

        assertEquals(listOf(ThreadAnchor("/src/Price.cs", LineRange(Side.RIGHT, 2, 2)) to "**Wrong case**\n\nUse `b`."), provider.createdThreads)
        assertEquals(1, pr.threads.value.size)
        assertEquals(emptyList<Any>(), pr.drafts.value)
    }

    @Test
    fun `passes the reviewer's failure on and refuses to send nothing`() = runBlocking {
        val (_, session) = session(FakeAiReviewer(failure = AiReviewException("Claude Code exited with code 1", "stderr")))
        val prepared = session.prepare()

        assertEquals("Claude Code exited with code 1", assertThrows<AiReviewException> { runBlocking { session.review(prepared) } }.message)
        assertEquals("No files to send", assertThrows<AiReviewException> {
            runBlocking { session.review(prepared, prepared.request.files.map { it.change.path }.toSet()) }
        }.message)
    }
}
