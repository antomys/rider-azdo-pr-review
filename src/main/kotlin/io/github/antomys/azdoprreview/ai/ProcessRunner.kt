package io.github.antomys.azdoprreview.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.time.Duration

/** Runs a child process with exactly the given environment, feeding it stdin and collecting its output. */
object ProcessRunner {
    /** [exitCode] is null when the process was killed because it ran past the timeout. */
    class Output(val exitCode: Int?, val stdout: String, val stderr: String)

    /**
     * Runs [command] in [directory] with only [environment], writes [input] to its stdin, and waits up to [timeout].
     * On timeout or cancellation the process and everything it started are killed.
     */
    suspend fun run(command: List<String>, environment: Map<String, String>, directory: File, input: ByteArray, timeout: Duration): Output =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(command).directory(directory)
            builder.environment().clear()
            builder.environment().putAll(environment)
            val process = builder.start()
            coroutineScope {
                val stdout = async { process.inputStream.readAllBytes() }
                val stderr = async { process.errorStream.readAllBytes() }
                launch {
                    // A process that exits without reading all of its input closes the pipe; that isn't an error here.
                    try {
                        process.outputStream.use { it.write(input) }
                    } catch (_: IOException) {
                    }
                }
                // Kill inside the scope: the readers return only once the process has exited.
                val exited = try {
                    withTimeoutOrNull(timeout) { process.onExit().await() } != null
                } finally {
                    if (process.isAlive) killTree(process)
                }
                Output(if (exited) process.exitValue() else null, stdout.await().decodeToString(), stderr.await().decodeToString())
            }
        }

    private fun killTree(process: Process) {
        // Children first: once the parent dies they are re-parented and no longer listed as its descendants.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
