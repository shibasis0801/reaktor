package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.HybridCheck
import dev.shibasis.reaktor.conductor.HybridCheckRun
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the planner's acceptance checks itself, instead of asking the executor whether it passed.
 *
 * The seat's weakest link has always been that "the tests pass" arrives as a sentence written by the
 * party with an interest in it passing. A check named by the planner and run by Reaktor is a
 * different kind of evidence: nobody in the loop authors the exit code.
 *
 * **The planner does not get to choose what runs.** It picks from [allowed], a list the operator
 * supplied with the task, and anything else is refused and reported as refused. That boundary is the
 * whole reason this is safe to expose to a connector: a model that can propose `./gradlew test` on a
 * task whose operator allowed `./gradlew` is choosing among sanctioned commands, not gaining a shell.
 */
internal object HybridChecks {

    fun run(workspace: File, checks: List<HybridCheck>, allowed: List<String>, timeoutSeconds: Long = 900): List<HybridCheckRun> =
        checks.take(MAX_CHECKS).map { check ->
            val permitted = allowed.any { rule -> check.command == rule || check.command.startsWith("$rule ") }
            if (!permitted) {
                HybridCheckRun(check.name, check.command, exitCode = null, ok = false,
                    output = "Refused: this task permits only " + allowed.joinToString(", ").ifBlank { "no check commands" } +
                        ". Ask the operator to allow it, or verify another way.")
            } else {
                execute(workspace, check, timeoutSeconds)
            }
        }

    private fun execute(workspace: File, check: HybridCheck, timeoutSeconds: Long): HybridCheckRun {
        val argv = tokenize(check.command)
        if (argv.isEmpty()) return HybridCheckRun(check.name, check.command, null, false, "Empty command")
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        return runCatching {
            // No shell: the command is argv, so quoting cannot turn one check into two.
            val process = ProcessBuilder(argv).directory(workspace).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return HybridCheckRun(check.name, check.command, null, false,
                    "Did not finish within ${timeoutSeconds}s. " + output.takeLast(OUTPUT_LIMIT),
                    durationMillis = elapsed())
            }
            val code = process.exitValue()
            HybridCheckRun(check.name, check.command, code, code == 0, output.takeLast(OUTPUT_LIMIT),
                durationMillis = elapsed())
        }.getOrElse { failure ->
            HybridCheckRun(check.name, check.command, null, false, "Could not run: ${failure.message}",
                durationMillis = elapsed())
        }
    }

    /** Splits a command line into argv, honouring single and double quotes and nothing else. */
    internal fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (character in command) {
            when {
                quote != null && character == quote -> quote = null
                quote != null -> current.append(character)
                character == '\'' || character == '"' -> quote = character
                character.isWhitespace() -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private const val MAX_CHECKS = 8
    private const val OUTPUT_LIMIT = 12000
}
