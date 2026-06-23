package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.io.File

/**
 * One dynamic subcommand per colon-namespaced script family in the project (e.g. `fastlane:*`,
 * `maestro:*`, `karate:*`, `k6:*`, `test:*`, `perf:*`). Reuses the project's existing npm scripts — nothing hardcoded.
 */
fun scriptFamilies(project: ReaktorProject?): List<CliktCommand> {
    if (project == null) return emptyList()
    val prefixes = project.scripts.keys
        .mapNotNull { it.substringBefore(':', "").ifEmpty { null } }
        .filter { it !in BUILT_IN_COMMANDS }
        .toSortedSet()
    return prefixes.map { ScriptFamily(it) }
}

/**
 * `reaktor <family> <segments…>` — e.g. `reaktor fastlane android release` runs the existing
 * `fastlane:android:release` script. With a partial path it lists the matching scripts.
 * The command name is the family prefix (Clikt 5 `CliktCommand(name)`).
 */
class ScriptFamily(private val prefix: String) : CliktCommand(prefix) {
    private val env by requireObject<ReaktorEnv>()
    private val path by argument(help = "script segments, e.g. `android release`").multiple()

    override fun run() {
        val p = env.requireProject()
        val family = p.scripts.keys.filter { it == prefix || it.startsWith("$prefix:") }.sorted()
        val parts = listOf(prefix) + path
        val exact = parts.indices
            .map { i -> parts.take(i + 1).joinToString(":") to parts.drop(i + 1) }
            .lastOrNull { (candidate, _) -> candidate in family }
        if (exact != null) {
            val (script, rest) = exact
            runChecked(env, p.directScriptCommand(script, rest) ?: throw UsageError("No script '$script'."))
            return
        }
        val candidate = parts.joinToString(":")
        val matches = family.filter { it.startsWith(candidate) }.ifEmpty { family }
        env.terminal.println(dim("no exact script `$candidate` — matching:"))
        matches.forEach { env.terminal.println("  reaktor " + it.replace(':', ' ') + "  " + dim("($it)")) }
    }
}

/** `reaktor self` — manage the installed CLI. */
class Self : CliktCommand() {
    override fun run() {}
}

/** `reaktor self uninstall` — remove `~/.reaktor` and the PATH entry from the shell rc. */
class Uninstall : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val home = System.getProperty("user.home")
        File(home, ".reaktor").takeIf { it.exists() }?.deleteRecursively()
        val rc = File(home, ".zshrc")
        if (rc.exists()) {
            val kept = rc.readLines().filterNot { it.contains("# reaktor-cli") }
            rc.writeText(kept.joinToString("\n") + "\n")
        }
        env.terminal.println(green("✓") + " removed ~/.reaktor and the PATH entry from ~/.zshrc. Restart your shell.")
    }
}

/** `reaktor self update` — rebuild from the recorded source checkout and reinstall. */
class Update : CliktCommand("update") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val ref = File(System.getProperty("user.home"), ".reaktor/source")
        if (!ref.exists()) throw UsageError(
            "Unknown source. Rebuild from your reaktor-cli checkout: " +
                "./gradlew installDist && ./build/install/reaktor/bin/reaktor install",
        )
        val source = File(ref.readText().trim())
        if (!File(source, "build.gradle.kts").exists()) throw CliktError("Source not found at ${source.path}.")
        env.terminal.println(dim("rebuilding from ") + source.path)
        if (env.runner.run(listOf("./gradlew", "installDist", "--console=plain", "--quiet"), source) != 0) {
            throw CliktError("build failed")
        }
        val fresh = File(source, "build/install/reaktor/bin/reaktor").absolutePath
        if (env.runner.run(listOf(fresh, "install"), source) != 0) throw CliktError("reinstall failed")
        env.terminal.println(green("✓") + " updated")
    }
}
