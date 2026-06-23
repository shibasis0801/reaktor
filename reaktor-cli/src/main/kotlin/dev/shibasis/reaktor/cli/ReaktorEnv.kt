package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The context shared down the Clikt command tree: the discovered project, the executor,
 * and one Mordant [Terminal] used for all rich output (tables, prompts, styled text).
 */
class ReaktorEnv private constructor(
    val terminal: Terminal,
    val runner: ProcessRunner,
    val project: ReaktorProject?,
) {
    fun requireProject(): ReaktorProject =
        project ?: throw CliktError(
            "Not inside a reaktor project. Add a \"reaktor\" key to your package.json " +
                "(see `reaktor doctor`)."
        )

    companion object {
        fun create(): ReaktorEnv {
            val terminal = Terminal()
            return ReaktorEnv(terminal, ProcessRunner(terminal), ReaktorProject.discover())
        }
    }
}
