package io.github.antomys.azdoprreview.ai

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs `/bin/sh`, never an AI CLI. */
@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {
    @TempDir
    lateinit var dir: File

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    private suspend fun sh(script: String, input: String = "", timeout: kotlin.time.Duration = 1.minutes) =
        ProcessRunner.run(listOf("/bin/sh", "-c", script), env, dir, input.toByteArray(), timeout)

    @Test
    fun `passes stdin and returns the exit code and both outputs`() = runBlocking {
        val output = sh("cat; echo err >&2; exit 3", input = "hello")
        assertEquals(3, output.exitCode)
        assertEquals("hello", output.stdout)
        assertEquals("err\n", output.stderr)
    }

    @Test
    fun `runs with only the given environment, in the given directory`() = runBlocking {
        val output = sh("env; pwd")
        val lines = output.stdout.lines()
        assertTrue(lines.none { it.startsWith("HOME=") }, output.stdout)
        assertTrue(lines.any { it == "PATH=/usr/bin:/bin" }, output.stdout)
        assertEquals(dir.canonicalPath, File(lines.last { it.isNotEmpty() }).canonicalPath)
    }

    @Test
    fun `ignores input the process doesn't read`() = runBlocking {
        assertEquals(0, sh("exit 0", input = "x".repeat(1_000_000)).exitCode)
    }

    @Test
    fun `kills the process tree on timeout`() = runBlocking {
        val started = System.nanoTime()
        val output = sh(SLEEPING_CHILD, timeout = 500.milliseconds)
        assertNull(output.exitCode)
        assertTrue(System.nanoTime() - started < 10.seconds.inWholeNanoseconds, "took too long")
        assertChildKilled()
    }

    @Test
    fun `kills the process tree when cancelled`() = runBlocking {
        val job = launch { sh(SLEEPING_CHILD) }
        delay(500)
        withTimeout(10.seconds) { job.cancelAndJoin() }
        assertChildKilled()
    }

    /** The shell's own child, which outlives the shell unless the whole tree is killed. */
    private suspend fun assertChildKilled() {
        val pid = File(dir, "child.pid").readText().trim().toLong()
        withTimeout(5.seconds) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50) }
    }

    private companion object {
        const val SLEEPING_CHILD = "sleep 30 & echo ${'$'}! > child.pid; wait"
    }
}
