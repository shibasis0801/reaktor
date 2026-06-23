package dev.shibasis.reaktor.cli

import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * C0 stand-in for reaktor-cloud's `ProcessToolRunner`. Supervises a subprocess, streams its
 * merged output to the terminal, and returns the exit code. A later phase replaces this with
 * the real reaktor-cloud runners (Process / Dagger / Pulumi) emitting `CloudEvent`s.
 */
class ProcessRunner(private val terminal: Terminal) {

    fun run(command: List<String>, cwd: File, echo: Boolean = true): Int {
        if (echo) terminal.println(dim("$ " + command.joinToString(" ")))
        return try {
            val process = ProcessBuilder(command)
                .directory(cwd)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { terminal.println(it) }
            process.waitFor()
        } catch (e: Exception) {
            terminal.println(red("! ${e.message}"))
            127
        }
    }

    /** Run to completion and capture merged output (used by `doctor`). */
    fun capture(command: List<String>, cwd: File): Pair<Int, String> = try {
        val process = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() to output
    } catch (e: Exception) {
        127 to (e.message ?: "error")
    }
}
