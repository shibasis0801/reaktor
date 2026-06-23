package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim

val BUILT_IN_COMMANDS = setOf(
    "tasks", "run", "logs", "dev", "build", "deploy", "test", "gradle",
    "doctor", "install", "self", "new", "add", "infra", "db", "cloud", "dagger", "graph",
    "auth", "explain", "docs", "engine",
)

private val DIRECT_SCRIPT_SHORTCUTS = setOf(
    "radar", "ssh", "memgraph", "clickhouse", "web",
)

fun shortcutCommands(project: ReaktorProject?): List<CliktCommand> {
    if (project == null) return emptyList()
    val reserved = (BUILT_IN_COMMANDS + project.families).toMutableSet()
    val commands = mutableListOf<CliktCommand>()

    fun reserve(name: String, command: CliktCommand) {
        if (name !in reserved) {
            commands += command
            reserved += name
        }
    }

    project.projectTargetNames.forEach { reserve(it, TargetShortcut(it)) }
    project.stores.filter { it in project.scripts }.sorted().forEach { reserve(it, StoreShortcut(it)) }
    project.libraries.forEach { reserve(it, GradleModuleShortcut(it)) }
    DIRECT_SCRIPT_SHORTCUTS.filter { it in project.scripts }.sorted().forEach { reserve(it, DirectScriptShortcut(it)) }

    return commands
}

class TargetShortcut(private val targetName: String) : CliktCommand(targetName) {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "dev | build | deploy | test | logs | npm | <family> [segments]").multiple()

    override fun run() {
        val p = env.requireProject()
        val action = args.firstOrNull()
        val rest = args.drop(1)
        if (action == null) {
            renderTargetDashboard(env, targetName)
            return
        }
        if (action in p.families && action !in setOf("test")) {
            if (rest.isEmpty()) {
                renderTargetFamily(env, targetName, action)
                return
            }
            val command = p.targetFamilyCommand(targetName, action, rest)
                ?: throw UsageError("No $action task resolved for '$targetName ${rest.joinToString(" ")}'.")
            runChecked(env, command)
            return
        }
        if (action == "npm") {
            if (rest.isEmpty()) {
                renderTargetDashboard(env, targetName)
                return
            }
            val command = p.workspaceScriptCommand(targetName, rest.first(), rest.drop(1))
                ?: throw UsageError("No npm workspace script '${rest.first()}' for '$targetName'.")
            runChecked(env, command)
            return
        }
        val command = when (action) {
            "dev" -> p.devCommand(targetName, rest)
            "build" -> p.buildCommand(targetName, rest)
            "deploy" -> p.deployCommand(targetName, rest)
            "test" -> if (rest.isEmpty()) p.targetTestCommand(targetName) else p.targetTestCommand(targetName, rest)
            "logs" -> p.logsCommand(targetName, rest)
            else -> null
        } ?: throw UsageError("Unknown action '$action' for target '$targetName'. Use dev, build, deploy, test, or logs.")
        runChecked(env, command)
    }
}

class ServiceShortcut(private val serviceName: String, private val kind: String) : CliktCommand(serviceName) {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "deploy | logs | build | <family> [segments]").multiple()

    override fun run() {
        val p = env.requireProject()
        val action = args.firstOrNull()
        val rest = args.drop(1)
        if (action == null) {
            renderTargetDashboard(env, serviceName, kind)
            return
        }
        if (action in p.families) {
            if (rest.isEmpty()) {
                renderTargetFamily(env, serviceName, action)
                return
            }
            val command = p.targetFamilyCommand(serviceName, action, rest)
                ?: throw UsageError("No $action task resolved for '$serviceName ${rest.joinToString(" ")}'.")
            runChecked(env, command)
            return
        }
        val command = when (action) {
            "build" -> p.buildCommand(serviceName, rest)
            "deploy" -> p.deployCommand(serviceName, rest)
            "logs" -> p.logsCommand(serviceName, rest)
            else -> null
        } ?: throw UsageError("Unknown action '$action' for $serviceName. Use build, deploy, or logs.")
        runChecked(env, command)
    }
}

class StoreShortcut(private val storeName: String) : CliktCommand(storeName) {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "extra args for the store console script").multiple()

    override fun run() {
        val p = env.requireProject()
        val command = p.storeConsoleCommand(storeName, args)
            ?: throw UsageError("No console script for '$storeName'. Try `reaktor db status`.")
        runChecked(env, command)
    }
}

class DirectScriptShortcut(private val scriptName: String) : CliktCommand(scriptName) {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "extra args for npm run $scriptName").multiple()

    override fun run() {
        val p = env.requireProject()
        val command = p.directScriptCommand(scriptName, args)
            ?: throw UsageError("No script '$scriptName'.")
        runChecked(env, command)
    }
}

class GradleModuleShortcut(private val moduleName: String) : CliktCommand(moduleName) {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "gradle task; show tasks when omitted").multiple()

    override fun run() {
        val p = env.requireProject()
        if (args.isEmpty()) {
            renderGradleModuleDashboard(env, moduleName)
            return
        }
        val command = p.buildCommand(moduleName, args)
            ?: throw UsageError("No gradle module '$moduleName'.")
        runChecked(env, command)
    }
}

fun runChecked(env: ReaktorEnv, command: ProjectCommand) {
    env.terminal.println(dim("→ ") + bold(command.label))
    val code = env.run(command)
    if (code != 0) throw CliktError("${command.label} exited with $code")
}

fun knownTopLevelNames(project: ReaktorProject?): Set<String> {
    if (project == null) return BUILT_IN_COMMANDS
    return BUILT_IN_COMMANDS +
        project.families +
        project.projectTargetNames +
        project.stores.filter { it in project.scripts } +
        project.libraries +
        DIRECT_SCRIPT_SHORTCUTS.filter { it in project.scripts }
}

fun printUnknownTopLevel(env: ReaktorEnv, name: String) {
    val p = env.project
    env.terminal.println("Error: no such command " + bold(name))
    val candidates = knownTopLevelNames(p)
        .map { it to editDistance(name, it) }
        .filter { (_, d) -> d <= 3 }
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(8)
        .map { it.first }
    if (candidates.isNotEmpty()) {
        env.terminal.println("Did you mean: " + candidates.joinToString("  ") { green(it) })
    }
    if (p != null) {
        env.terminal.println(dim("Try `reaktor`, `reaktor tasks`, or `reaktor explain <name>`."))
    } else {
        env.terminal.println(dim("Try `reaktor --help`."))
    }
}

private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + cost,
            )
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return previous[b.length]
}
