package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.mordant.terminal.Terminal
import dev.shibasis.reaktor.tooling.DiscoveredJvmWorkspace
import dev.shibasis.reaktor.tooling.JvmProjectDiscovery
import java.io.File

/**
 * The context shared down the Clikt command tree: the discovered project, the executor,
 * and one Mordant [Terminal] used for all rich output (tables, prompts, styled text).
 */
class ReaktorEnv private constructor(
    val terminal: Terminal,
    val runner: ProcessRunner,
    val project: ReaktorProject?,
    val toolingWorkspace: DiscoveredJvmWorkspace?,
) {
    fun requireProject(): ReaktorProject =
        project ?: throw CliktError(
            "Not inside a reaktor project. Add a \"reaktor\" key to your package.json " +
                "(see `reaktor doctor`)."
        )

    companion object {
        fun create(): ReaktorEnv {
            val terminal = Terminal()
            val toolingWorkspace = JvmProjectDiscovery().discoverWorkspace()
            val project = toolingWorkspace?.catalog?.workspace?.root
                ?.let(::File)
                ?.let(ReaktorProject::discover)
            return ReaktorEnv(terminal, ProcessRunner(terminal), project, toolingWorkspace)
        }
    }
}
