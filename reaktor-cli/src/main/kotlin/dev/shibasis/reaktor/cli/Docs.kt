package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import dev.shibasis.reaktor.tooling.platformExecutable
import java.io.File

/**
 * `reaktor docs` — serve the project's Docusaurus docs locally (the dev server, with HMR). This is
 * the local view, so it picks up the local-only `private/` folder; the production build excludes it.
 * `reaktor docs build` / `reaktor docs serve` run the other docusaurus scripts.
 */
class Docs : CliktCommand("docs") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "docusaurus script (default: start) + extra args").multiple()

    override fun run() {
        val p = env.requireProject()
        val command = p.docsCommand(args)
            ?: throw UsageError("No Docusaurus site found in this project (looked for a docusaurus.config.* file).")
        if (!File(command.cwd, "node_modules").isDirectory) {
            throw UsageError("Docs dependencies are missing in ${command.cwd}; run npm install there first.")
        }
        runChecked(env, command)
    }
}

/** Build the command that runs a docusaurus npm script (default `start`) in the project's docs site. */
fun ReaktorProject.docsCommand(args: List<String> = emptyList()): ProjectCommand? {
    val docsDir = docusaurusDir() ?: return null
    val script = args.firstOrNull() ?: "start"
    val rest = if (args.isEmpty()) emptyList() else args.drop(1)
    val npmArgs = listOf("run", script, "--") + rest
    val relative = runCatching { root.toPath().relativize(docsDir.toPath()).toString() }.getOrNull() ?: docsDir.path
    // Dependency installation is explicit in [Docs.run]. Execution stays argv-only so arguments
    // are never interpreted by a shell and npm.cmd works on Windows.
    return ProjectCommand(
        label = "docs · npm ${npmArgs.joinToString(" ")}  ($relative)",
        command = listOf(platformExecutable("npm")) + npmArgs,
        cwd = docsDir,
    )
}

/** Locate the project's Docusaurus site — common spots first, then a heavy-dir-skipping walk. */
private fun ReaktorProject.docusaurusDir(): File? {
    fun hasConfig(dir: File) =
        dir.isDirectory && dir.listFiles()?.any { it.name.startsWith("docusaurus.config.") } == true

    listOf("docusaurus", "website", "docs").map { File(root, it) }.firstOrNull(::hasConfig)?.let { return it }
    File(root, "targets").listFiles()?.filter { it.isDirectory }?.forEach { target ->
        File(target, "docusaurus").takeIf(::hasConfig)?.let { return it }
    }

    val skip = setOf("node_modules", "build", "dist", ".git", ".gradle", ".docusaurus", ".idea", ".kotlin", ".next")
    return root.walkTopDown()
        .onEnter { it.name !in skip }
        .firstOrNull { it.isFile && it.name.startsWith("docusaurus.config.") }
        ?.parentFile
}
