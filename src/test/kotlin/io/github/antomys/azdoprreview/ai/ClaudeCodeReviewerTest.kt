package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.ChangeKind
import io.github.antomys.azdoprreview.review.FileChange
import io.github.antomys.azdoprreview.review.Side
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The adapter runs fake `claude` shell scripts here; the real CLI is never started. */
class ClaudeCodeReviewerTest {
    @TempDir
    lateinit var dir: File

    private val request = AiReviewRequest(
        "Add pricing", "Adds discounts",
        listOf(FileDiff(FileChange("/src/a.cs", "/src/a.cs", ChangeKind.MODIFIED), "--- a/src/a.cs\n+++ b/src/a.cs\n@@ -1 +1 @@\n-x\n+y\n", 1, 1)),
        "Focus on security.", 20,
    )

    @Test
    fun `runs print mode with JSON output, a schema, no tools, no MCP servers and no saved session`() {
        assertEquals(
            listOf(
                "-p", "--tools", "", "--strict-mcp-config", "--no-session-persistence",
                "--output-format", "json", "--json-schema", AiReviewPrompt.FINDINGS_SCHEMA,
            ),
            ClaudeCodeReviewer.arguments(AiReviewPrompt.FINDINGS_SCHEMA),
        )
        val shown = ClaudeCodeReviewer("/opt/claude", environment = emptyMap(), home = dir.path).commandLine()
        assertEquals("claude -p --tools \"\" --strict-mcp-config --no-session-persistence --output-format json --json-schema '<findings schema>' < prompt", shown)
    }

    @Test
    fun `passes only basic and Claude sign-in variables, never other secrets`() {
        val parent = mapOf(
            "PATH" to "/usr/bin", "HOME" to "/home/me", "USER" to "me", "LANG" to "en_US.UTF-8", "TMPDIR" to "/tmp/",
            "ANTHROPIC_API_KEY" to "key", "CLAUDE_CODE_OAUTH_TOKEN" to "oauth", "HTTPS_PROXY" to "http://proxy",
            "AZDO_PAT" to "pat", "SYSTEM_ACCESSTOKEN" to "t", "GITHUB_TOKEN" to "gh", "AWS_SECRET_ACCESS_KEY" to "aws",
            "NPM_TOKEN" to "npm", "MY_SECRET" to "s", "JAVA_HOME" to "/jdk", "IDEA_VM_OPTIONS" to "x",
        )
        val env = ClaudeCodeReviewer.childEnvironment(parent, "/home/me")
        assertEquals(
            mapOf(
                "PATH" to "/usr/bin${File.pathSeparator}/home/me/.local/bin", "HOME" to "/home/me", "USER" to "me", "LANG" to "en_US.UTF-8",
                "TMPDIR" to "/tmp/", "ANTHROPIC_API_KEY" to "key", "CLAUDE_CODE_OAUTH_TOKEN" to "oauth", "HTTPS_PROXY" to "http://proxy",
            ),
            env,
        )
        assertEquals("/home/me", ClaudeCodeReviewer.childEnvironment(emptyMap(), "/home/me")["HOME"])
        assertEquals("error *** here", ClaudeCodeReviewer.redact("error sk-ant-0123456789 here", mapOf("ANTHROPIC_API_KEY" to "sk-ant-0123456789")))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `finds the CLI on PATH, then in local bin, or at the configured path`() {
        val onPath = script(File(dir, "bin"), "claude", "exit 0")
        val inLocalBin = script(File(dir, "home/.local/bin"), "claude", "exit 0")
        val home = File(dir, "home").path

        assertEquals(onPath, ClaudeCodeReviewer("", environment = mapOf("PATH" to onPath.parent), home = home).executable())
        assertEquals(inLocalBin, ClaudeCodeReviewer("", environment = mapOf("PATH" to "/nowhere"), home = home).executable())
        assertEquals(inLocalBin, ClaudeCodeReviewer("~/.local/bin/claude", environment = emptyMap(), home = home).executable())
        assertNull(ClaudeCodeReviewer("/nowhere/claude", environment = emptyMap(), home = home).executable())
        assertNull(ClaudeCodeReviewer("", environment = mapOf("PATH" to "/nowhere"), home = File(dir, "empty").path).executable())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `reports the version, or why the CLI is unavailable`() {
        val cli = script(dir, "claude", """[ "${'$'}1" = "--version" ] && echo "2.1.306 (Claude Code)"""")
        assertEquals(Availability.Available("2.1.306 (Claude Code)", cli.path), ClaudeCodeReviewer(cli.path, environment = env()).availability())
        val missing = ClaudeCodeReviewer("", environment = mapOf("PATH" to "/nowhere"), home = File(dir, "empty").path).availability()
        assertEquals(Availability.Unavailable("Claude Code CLI not found on PATH or in ~/.local/bin"), missing)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `sends the prompt on stdin from an empty temporary directory and parses the answer`() = runBlocking {
        val record = File(dir, "record").apply { mkdirs() }
        val envelope = """{"type":"result","is_error":false,"result":"","structured_output":{"findings":[""" +
            """{"path":"src/a.cs","side":"new","startLine":1,"endLine":1,"severity":"warning","title":"T","body":"B"}]}}"""
        val cli = script(
            dir, "claude",
            """
            pwd > '${record.path}/pwd'
            ls -A > '${record.path}/ls'
            env > '${record.path}/env'
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > '${record.path}/args'
            cat > '${record.path}/prompt'
            printf '%s' '$envelope'
            """.trimIndent(),
        )
        val parent = env() + mapOf("AZDO_PAT" to "secret-pat", "GITHUB_TOKEN" to "gh", "ANTHROPIC_API_KEY" to "sk-test")

        val result = ClaudeCodeReviewer(cli.path, environment = parent, home = dir.path).review(request)

        assertEquals(listOf(AiFinding("src/a.cs", Side.RIGHT, 1, 1, Severity.WARNING, "T", "B")), result.findings)
        assertEquals(ClaudeCodeReviewer.arguments(AiReviewPrompt.FINDINGS_SCHEMA), File(record, "args").readText().removeSuffix("\n").split("\n"))
        val env = File(record, "env").readText()
        assertFalse("secret-pat" in env || "AZDO_PAT" in env || "GITHUB_TOKEN" in env, env)
        assertTrue("ANTHROPIC_API_KEY=sk-test" in env, env)
        val workDir = File(File(record, "pwd").readText().trim())
        assertTrue(workDir.name.startsWith("azdo-ai-review"), workDir.path)
        assertFalse(workDir.exists(), "the temporary directory is deleted afterwards")
        assertEquals("", File(record, "ls").readText())
        val prompt = File(record, "prompt").readText()
        assertTrue("+++ b/src/a.cs" in prompt && "Focus on security." in prompt && "Pull request title: Add pricing" in prompt, prompt)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `reports a failed run with the end of stderr, without the sign-in key`() = runBlocking {
        val cli = script(dir, "claude", "cat > /dev/null; echo 'boom: key sk-test-123456 rejected' >&2; exit 2")
        val e = assertThrows<AiReviewException> {
            ClaudeCodeReviewer(cli.path, environment = env() + ("ANTHROPIC_API_KEY" to "sk-test-123456"), home = dir.path).review(request)
        }
        assertEquals("Claude Code exited with code 2", e.message)
        assertEquals("boom: key *** rejected", e.details)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `prefers the CLI's own error message to its exit code`() = runBlocking {
        val cli = script(dir, "claude", """cat > /dev/null; printf '%s' '{"type":"result","is_error":true,"result":"Not logged in · Please run /login"}'; exit 1""")
        val e = assertThrows<AiReviewException> { ClaudeCodeReviewer(cli.path, environment = env(), home = dir.path).review(request) }
        assertTrue(e.message!!.startsWith("Claude Code failed: Not logged in"), e.message)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `stops a run that takes too long`() = runBlocking {
        val cli = script(dir, "claude", "sleep 30; echo late")
        val e = assertThrows<AiReviewException> {
            ClaudeCodeReviewer(cli.path, timeout = 300.milliseconds, environment = env(), home = dir.path).review(request)
        }
        assertEquals("Claude Code didn't finish within 300ms", e.message)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `deletes the temporary directory when cancelled`() = runBlocking {
        val pwd = File(dir, "pwd")
        val cli = script(dir, "claude", "pwd > '${pwd.path}'; sleep 30")
        val job = launch { ClaudeCodeReviewer(cli.path, environment = env(), home = dir.path).review(request) }
        withTimeout(10.seconds) { while (!pwd.exists() || pwd.length() == 0L) delay(20) }
        withTimeout(10.seconds) { job.cancelAndJoin() }
        assertFalse(File(pwd.readText().trim()).exists())
    }

    private fun env() = mapOf("PATH" to "/usr/bin:/bin", "HOME" to dir.path)

    private fun script(folder: File, name: String, body: String): File =
        File(folder.apply { mkdirs() }, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }
}
