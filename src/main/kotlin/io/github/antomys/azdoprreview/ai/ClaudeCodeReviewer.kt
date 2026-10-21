package io.github.antomys.azdoprreview.ai

import io.github.antomys.azdoprreview.review.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the Claude Code CLI (`claude -p`) under the user's own Claude account. The prompt goes in on stdin; the CLI
 * gets no tools, no MCP servers, no saved session, an empty temporary working directory, and an environment without
 * the IDE's secrets. Flags verified with `claude --help` 2.1.306 (see docs/engineering-notes.md).
 *
 * [configuredPath] is the CLI from the settings; empty means `claude` on [environment]'s PATH, then `~/.local/bin`
 * (an IDE started from the Dock doesn't get the shell's PATH).
 */
class ClaudeCodeReviewer(
    private val configuredPath: String,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val environment: Map<String, String> = System.getenv(),
    private val home: String = System.getProperty("user.home"),
) : AiReviewer {
    override val id = "claude-code"
    override val name = "Claude Code"

    /** The CLI to run, or null if it can't be found. */
    fun executable(): File? {
        val configured = configuredPath.trim()
        if (configured.isNotEmpty()) return File(configured.replaceFirst(Regex("^~(?=/|$)"), home)).takeIf { it.canExecute() }
        val dirs = environment["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() } + "$home/.local/bin"
        val names = if (isWindows) listOf("claude.exe", "claude.cmd") else listOf("claude")
        return dirs.asSequence().flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile && it.canExecute() }
    }

    override fun availability(): Availability {
        val cli = executable() ?: return Availability.Unavailable(
            if (configuredPath.isBlank()) "Claude Code CLI not found on PATH or in ~/.local/bin" else "Claude Code CLI not found at ${configuredPath.trim()}",
        )
        return try {
            val process = ProcessBuilder(cli.path, "--version").redirectErrorStream(true).also {
                it.environment().clear()
                it.environment().putAll(childEnvironment(environment, home))
            }.start()
            process.outputStream.close()
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Availability.Unavailable("${cli.path} --version didn't answer")
            }
            val output = process.inputStream.readAllBytes().decodeToString().trim()
            if (process.exitValue() == 0) Availability.Available(output.lineSequence().first(), cli.path)
            else Availability.Unavailable("${cli.path} --version failed: ${tail(output)}")
        } catch (e: Exception) {
            Availability.Unavailable("Can't run ${cli.path}: ${e.message}")
        }
    }

    override fun commandLine(): String =
        (listOf(executable()?.path ?: "claude") + arguments("'<findings schema>'")).joinToString(" ") { if (it.isEmpty()) "\"\"" else it } +
            " < prompt"

    override suspend fun review(request: AiReviewRequest): AiReviewResult {
        val cli = executable() ?: throw AiReviewException("Claude Code CLI not found. Set its path in Settings | Tools | Azure DevOps PR Review.")
        val env = childEnvironment(environment, home)
        // An empty directory: the CLI has no tools, and even if it had, there would be nothing else to read here.
        val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("azdo-ai-review").toFile() }
        try {
            val prompt = AiReviewPrompt.build(request)
            val output = ProcessRunner.run(listOf(cli.path) + arguments(AiReviewPrompt.FINDINGS_SCHEMA), env, directory, prompt.toByteArray(), timeout)
            val stderr = redact(tail(output.stderr), env)
            if (output.exitCode == null) throw AiReviewException("Claude Code didn't finish within $timeout", stderr)
            return withContext(Dispatchers.Default) {
                try {
                    ClaudeOutput.parse(output.stdout)
                } catch (e: ClaudeOutput.ParseException) {
                    // The envelope's own error is clearer than the exit code; without one, report the exit code.
                    if (output.exitCode != 0 && !e.fromEnvelope) {
                        throw AiReviewException("Claude Code exited with code ${output.exitCode}", stderr.ifEmpty { redact(tail(output.stdout), env) })
                    }
                    throw AiReviewException(e.message.orEmpty(), redact(e.details ?: stderr, env))
                }
            }
        } finally {
            // Also after a cancel, which would otherwise skip the block.
            withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    companion object {
        val DEFAULT_TIMEOUT = 5.minutes
        private const val VERSION_TIMEOUT_SECONDS = 15L
        private const val TAIL_CHARS = 2000
        private val isWindows = System.getProperty("os.name").startsWith("Windows")

        /**
         * `-p` prints one answer and exits; `--tools ""` disables every built-in tool and `--strict-mcp-config` (without
         * `--mcp-config`) loads no MCP servers, so the CLI can only read what is in the prompt.
         */
        fun arguments(schema: String): List<String> = listOf(
            "-p",
            "--tools", "",
            "--strict-mcp-config",
            "--no-session-persistence",
            "--output-format", "json",
            "--json-schema", schema,
        )

        /** Variables passed through as they are: what a process needs to run, plus proxies and certificates for its connection. */
        private val PASSED = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP",
            "SYSTEMROOT", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy", "NODE_EXTRA_CA_CERTS",
        )

        /**
         * Claude Code's own sign-in and configuration. With none of these it uses the login stored in the OS keychain
         * (macOS) or its config directory, which needs only HOME and USER.
         */
        val CLAUDE_AUTH = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR")

        /**
         * The child's environment: an allow-list of [parent], never the Azure DevOps PAT or other secrets. `~/.local/bin`
         * is added to PATH because the CLI's installer puts it there.
         */
        fun childEnvironment(parent: Map<String, String>, home: String): Map<String, String> {
            val env = parent.filterKeys { it in PASSED || it in CLAUDE_AUTH }.toMutableMap()
            val localBin = "$home/.local/bin"
            val path = env["PATH"].orEmpty()
            if (localBin !in path.split(File.pathSeparatorChar)) env["PATH"] = listOf(path, localBin).filter { it.isNotEmpty() }.joinToString(File.pathSeparator)
            env.putIfAbsent("HOME", home)
            return env
        }

        /** The last part of [text], for error messages. */
        fun tail(text: String): String = text.trim().takeLast(TAIL_CHARS)

        /** Replaces the values of the auth variables passed to the CLI, in case it echoes one. */
        fun redact(text: String, env: Map<String, String>): String =
            env.filterKeys { it in CLAUDE_AUTH && it != "ANTHROPIC_BASE_URL" && it != "CLAUDE_CONFIG_DIR" }.values
                .filter { it.length >= 8 }
                .fold(text) { result, secret -> result.replace(secret, "***") }
    }
}

/**
 * Reads `claude -p --output-format json` output. **UNVERIFIED** shape (written from the documentation, not from a run):
 * one JSON object with `type: "result"`, `is_error`, `result` (the answer text) and, with `--json-schema`,
 * `structured_output` (the answer as JSON). Tolerant: takes `structured_output`, else parses `result` as JSON (also
 * inside a ```json fence); a JSON array of messages is searched for its `result` message.
 */
object ClaudeOutput {
    private val json = Json { ignoreUnknownKeys = true }
    private val FENCE = Regex("""```(?:json)?\s*\n?(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
    private val SIGN_IN = Regex("""log ?in|sign ?in|api key|authenticat|oauth""", RegexOption.IGNORE_CASE)

    fun parse(stdout: String): AiReviewResult {
        val root = parseJson(stdout.trim()) ?: throw ParseException("Claude Code returned output that isn't JSON", ClaudeCodeReviewer.tail(stdout))
        val envelope = when (root) {
            is JsonObject -> root
            is JsonArray -> root.filterIsInstance<JsonObject>().lastOrNull { it.string("type") == "result" }
            else -> null
        } ?: throw ParseException("Claude Code's output has no result", ClaudeCodeReviewer.tail(stdout))
        val resultText = envelope.string("result")
        if ((envelope["is_error"] as? JsonPrimitive)?.booleanOrNull == true) {
            val message = resultText ?: envelope.string("subtype") ?: "unknown error"
            val hint = if (SIGN_IN.containsMatchIn(message)) " Run claude in a terminal and sign in, then try again." else ""
            throw ParseException("Claude Code failed: ${message.take(300)}.$hint", null, fromEnvelope = true)
        }
        val answer = envelope["structured_output"]?.let(::asObject)
            ?: resultText?.let { text -> asObject(JsonPrimitive(text)) }
            // Envelopes without a "result" may be the answer itself.
            ?: envelope.takeIf { "findings" in it }
            ?: throw ParseException("Claude Code's answer isn't the expected JSON", ClaudeCodeReviewer.tail(resultText ?: stdout), fromEnvelope = true)
        val items = answer["findings"] as? JsonArray
            ?: throw ParseException("Claude Code's answer has no findings list", ClaudeCodeReviewer.tail(resultText ?: stdout), fromEnvelope = true)
        val findings = items.mapNotNull { (it as? JsonObject)?.let(::finding) }
        return AiReviewResult(findings, skipped = items.size - findings.size, rawLog = resultText)
    }

    /** A JSON object, or a string holding one (possibly fenced or with text around it). */
    private fun asObject(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> element.contentOrNull?.let { text ->
            val candidates = listOfNotNull(
                text.trim(),
                FENCE.find(text)?.groupValues?.get(1)?.trim(),
                text.indexOf('{').let { start -> text.lastIndexOf('}').takeIf { start >= 0 && it > start }?.let { text.substring(start, it + 1) } },
            )
            candidates.firstNotNullOfOrNull { parseJson(it) as? JsonObject }
        }
        else -> null
    }

    /** Lenient on what can be defaulted; null (skipped) without a path or start line. */
    private fun finding(item: JsonObject): AiFinding? {
        val path = item.string("path")?.takeIf { it.isNotBlank() } ?: return null
        val start = (item["startLine"] as? JsonPrimitive)?.intOrNull ?: return null
        val end = (item["endLine"] as? JsonPrimitive)?.intOrNull ?: start
        val side = if (item.string("side").equals("old", ignoreCase = true)) Side.LEFT else Side.RIGHT
        val severity = when (item.string("severity")?.lowercase()) {
            "error" -> Severity.ERROR
            "info" -> Severity.INFO
            else -> Severity.WARNING
        }
        return AiFinding(path, side, start, end, severity, item.string("title").orEmpty(), item.string("body").orEmpty())
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseJson(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    /** [fromEnvelope]: the CLI produced a readable envelope, so its message explains the failure better than an exit code. */
    class ParseException(message: String, details: String?, val fromEnvelope: Boolean = false) : AiReviewException(message, details)
}
