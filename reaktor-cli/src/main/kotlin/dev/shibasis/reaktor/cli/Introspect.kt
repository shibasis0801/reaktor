package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

/** `reaktor explain <name>` — describe a target, script, or script family. */
class Explain : CliktCommand("explain") {
    private val env by requireObject<ReaktorEnv>()
    private val name by argument(help = "a target, script, or family name")
    override fun run() {
        val p = env.requireProject()
        val n = name
        val target = p.declaredTargetForName(n)
        if (target != null || p.projectTarget(n) != null) {
            renderTargetDashboard(env, n)
            return
        }
        if (n in p.gradleModules) {
            renderGradleModuleDashboard(env, n)
            return
        }
        val cmd = p.scripts[n]
        if (cmd != null) {
            val invoke = if (n.contains(':')) "reaktor ${n.replace(':', ' ')}" else "reaktor run $n"
            env.terminal.println(bold("script ") + n + dim("   ($invoke)"))
            env.terminal.println(dim("  → ") + cmd)
            return
        }
        val family = p.scripts.keys.filter { it.startsWith("$n:") }.sorted()
        if (family.isNotEmpty()) {
            env.terminal.println(bold("family ") + n)
            family.forEach { env.terminal.println("  reaktor " + it.replace(':', ' ') + dim("  ($it)")) }
            return
        }
        throw UsageError("Unknown '$n'. Try `reaktor tasks`.")
    }
}

/** `reaktor graph` — render the project's declared model as a graph, derived from files it already has. */
class Graph : CliktCommand("graph") {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val p = env.requireProject()
        val t = env.terminal
        t.println(bold(p.name) + dim("  — project graph (derived from the reaktor key + files)"))
        fun section(label: String, items: List<String>) {
            t.println(bold(label) + dim(" (${items.size})"))
            if (items.isEmpty()) t.println(dim("  —")) else items.forEach { t.println("  • $it") }
        }
        section("targets", p.projectTargetNames)
        section("services", p.services)
        section("stores", p.stores)
        section("modules", p.modules)
        section("families", p.scripts.keys.mapNotNull { it.substringBefore(':', "").ifEmpty { null } }.distinct().sorted())
        section("gradle modules", p.gradleModules)
    }
}
