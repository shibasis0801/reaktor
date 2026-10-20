package dev.shibasis.dependeasy.model.device

import dev.shibasis.dependeasy.model.BuildOption

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object CommandRunner {

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
        directory: File? = null,
        environment: Map<String, String>? = null,
    ): Result = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(argv).directory(directory).redirectErrorStream(true)
        environment?.let { values -> builder.environment().apply { clear(); putAll(values) } }
        val process = builder.start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${argv.first()} timed out after ${timeoutSeconds}s")
        }
        Result(process.exitValue(), output.get(), "")
    }

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

    fun locate(name: String, directories: List<String> = BuildOption.environmentSnapshot()["PATH"].orEmpty().split(File.pathSeparator)): String? =
        directories.asSequence().filter(String::isNotBlank).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.absolutePath
}
