package dev.shibasis.reaktor.tooling.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a fixed argv vector and returns what it said.
 *
 * Apple's tools are the one place where a subprocess is the right answer rather than a
 * compromise: `devicectl` states that its JSON output file is the only supported programmatic
 * interface, and `simctl` has no library form at all. There is no shell here — argv is passed
 * through, so nothing in a device name or a bundle id can be reinterpreted as syntax.
 */
internal object CommandRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val succeeded: Boolean get() = exitCode == 0

        fun requireSuccess(what: String): String {
            require(succeeded) { "$what failed (exit $exitCode): ${stderr.trim().ifBlank { stdout.trim() }}" }
            return stdout
        }
    }

    suspend fun run(
        argv: List<String>,
        timeoutSeconds: Long = 60,
    ): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${argv.first()} timed out after ${timeoutSeconds}s")
        }
        Result(process.exitValue(), stdout, stderr)
    }

    /** Streams stdout a line at a time, for tools that follow rather than finish. */
    fun stream(argv: List<String>): Flow<String> = flow {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { emit(it) }
            }
        } finally {
            process.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Runs a tool that writes its real answer to a file rather than to stdout.
     *
     * `devicectl` is explicit that stdout is for humans and may change between releases, while the
     * JSON file is versioned and stable. Reading the file is therefore the only correct way to
     * consume it, however convenient piping would be.
     */
    suspend fun runJson(argv: List<String>, timeoutSeconds: Long = 60): String {
        val output = File.createTempFile("reaktor-devicectl", ".json")
        return try {
            val result = run(argv + listOf("--json-output", output.absolutePath), timeoutSeconds)
            val text = output.takeIf { it.length() > 0 }?.readText().orEmpty()
            if (!result.succeeded && text.isBlank()) {
                error("${argv.joinToString(" ")} failed: ${result.stderr.trim().ifBlank { result.stdout.trim() }}")
            }
            text
        } finally {
            output.delete()
        }
    }

    /**
     * Looks a binary up on PATH and the usual install roots.
     *
     * The Python user-base directories are in the list because `pip install --user fb-idb` puts
     * `idb` in `~/Library/Python/<version>/bin`, which is not on a login shell's PATH by default.
     * A tool that is installed and invisible reports as "not installed", which sends the
     * developer to install it again.
     */
    fun locate(name: String): String? {
        val home = System.getProperty("user.home").orEmpty()
        val candidates = buildList {
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
                .forEach { add(File(it, name)) }
            listOf("/usr/bin", "/opt/homebrew/bin", "/usr/local/bin").forEach { add(File(it, name)) }
            add(File("$home/.local/bin", name))
            File("$home/Library/Python").listFiles()
                ?.sortedByDescending(File::getName)
                ?.forEach { add(File(it, "bin/$name")) }
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
