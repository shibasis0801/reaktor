package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.YesNoPrompt
import java.io.File

/** `reaktor tasks` — the project model (targets, workers, servers, stores, families), organized. */
class Tasks : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        env.requireProject()
        renderProject(env, includeCommands = false)
    }
}

/** `reaktor run [script]` — run an npm script; with no name, list them all in one place. */
class Run : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val name by argument(help = "an npm script name; omit to list all").optional()
    private val args by argument(help = "extra args passed after `npm run <script> --`").multiple()
    override fun run() {
        val p = env.requireProject()
        val n = name
        if (n == null) {
            env.terminal.println(bold("npm scripts (${p.scripts.size})"))
            p.scripts.keys.sorted().chunked(3).forEach { env.terminal.println("  " + it.joinToString("   ")) }
            return
        }
        if (n !in p.scripts) throw UsageError("No script '$n'. Run `reaktor run` to list them.")
        runChecked(env, p.directScriptCommand(n, args) ?: throw UsageError("No script '$n'."))
    }
}

/** `reaktor logs <service>` — tail a worker: reuse a `logs<Name>` script if present, else `wrangler tail`. */
class Logs : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val service by argument(help = "service/worker name to tail")
    private val args by argument(help = "extra args for the log command").multiple()
    override fun run() {
        val p = env.requireProject()
        val command = p.logsCommand(service, args) ?: ProjectCommand(
            label = "npx wrangler tail $service",
            command = listOf("npx", "wrangler", "tail", service) + args,
            cwd = p.root,
        )
        runChecked(env, command)
    }
}

/** `reaktor dev [target]` — run a declared target's dev script. */
class Dev : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val target by argument().optional()
    private val args by argument(help = "extra args for the dev script").multiple()
    override fun run() {
        val p = env.requireProject()
        val t = resolveTarget(env, p, target)
        val command = p.devCommand(t.name, args) ?: throw UsageError("Target '${t.name}' has no dev script")
        runChecked(env, command)
    }
}

/** `reaktor build [target|module]` — build a declared target, or a gradle module via `:<module>:build`. */
class Build : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val target by argument().optional()
    private val args by argument(help = "extra args; for modules the first arg is the task").multiple()
    override fun run() {
        val p = env.requireProject()
        val n = target
        val command = if (n == null) {
            p.buildCommand(resolveTarget(env, p, null).name, args)
        } else {
            p.buildCommand(n, args)
        } ?: throw UsageError("No build for '${n ?: "target"}'. Try `reaktor tasks`.")
        runChecked(env, command)
    }
}

/**
 * `reaktor deploy [target|service]` — runs straight away (no prompt). Resolves a declared
 * target's deploy script, or a `deploy<Name>` service script (e.g. `reaktor deploy chat`).
 * With no target, opens an interactive fuzzy selector over every resolved deploy option.
 * `--confirm`/`-i` asks first; `--dry-run`/`-n` previews without running.
 */
class Deploy : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    private val target by argument(help = "a declared target, or a service with a deploy<Name> script").optional()
    private val args by argument(help = "extra args for the deploy command").multiple()
    private val confirm by option("--confirm", "-i", help = "ask for confirmation before running").flag()
    private val dryRun by option("--dry-run", "-n", help = "show what would run, don't run it").flag()
    override fun run() {
        val p = env.requireProject()
        val selectedTarget = target ?: selectDeployTarget(env, p, args)
        val t = p.declaredTargetForName(selectedTarget)
        val command = p.deployCommand(selectedTarget, args) ?: throw UsageError(
            "No deploy for '$selectedTarget'. Try `reaktor tasks` or add a target deploy, deploy<Name> script, or targets/$selectedTarget/deploy.sh."
        )
        if (dryRun) {
            env.terminal.println(dim("dry run") + " — would run: " + bold(command.label))
            return
        }
        if (confirm) {
            env.terminal.println(table {
                header { row("target", "runtime", "via") }
                body { row(selectedTarget, t?.runtime ?: "service", command.label) }
            })
            if (YesNoPrompt("Proceed?", env.terminal).ask() != true) throw Abort()
        }
        runChecked(env, command)
    }
}

/** `reaktor test [target|module|script segments]` — run declared tests, test:* scripts, or gradle tests. */
class Test : CliktCommand("test") {
    private val env by requireObject<ReaktorEnv>()
    private val path by argument(help = "target, gradle module, or test:* script segments").multiple()
    override fun run() {
        val p = env.requireProject()
        if (path.isEmpty()) {
            env.terminal.println(bold("tests"))
            val targetTests = p.projectTargetNames.filter { p.targetTestCommand(it) != null }
            if (targetTests.isNotEmpty()) {
                env.terminal.println(dim("  targets"))
                targetTests.forEach { env.terminal.println("    reaktor test $it") }
            }
            val scripts = p.scripts.keys.filter { it == "test" || it.startsWith("test:") }.sorted()
            if (scripts.isNotEmpty()) {
                env.terminal.println(dim("  scripts"))
                scripts.forEach { env.terminal.println("    reaktor " + it.replace(':', ' ')) }
            }
            if (p.gradleModules.isNotEmpty()) {
                env.terminal.println(dim("  gradle modules"))
                p.gradleModules.forEach { env.terminal.println("    reaktor test $it") }
            }
            return
        }
        val command = p.testCommand(path)
            ?: throw UsageError("No test resolved for '${path.joinToString(" ")}'. Run `reaktor test` to list options.")
        runChecked(env, command)
    }
}

/** `reaktor gradle [module] [task]` — list modules or run a module task. */
class Gradle : CliktCommand("gradle") {
    private val env by requireObject<ReaktorEnv>()
    private val args by argument(help = "module task..., or raw gradle args like `tasks`").multiple()
    override fun run() {
        val p = env.requireProject()
        if (args.isEmpty()) {
            renderGradleOverview(env, p)
            return
        }
        val first = args.first()
        val command = if (first in p.gradleModules) {
            p.buildCommand(first, args.drop(1))
        } else {
            ProjectCommand(
                label = "./gradlew ${args.joinToString(" ")}",
                command = listOf("./gradlew") + args,
                cwd = p.root,
            )
        } ?: throw UsageError("No gradle command for '${args.joinToString(" ")}'.")
        runChecked(env, command)
    }
}

private fun renderGradleOverview(env: ReaktorEnv, p: ReaktorProject) {
    val t = env.terminal
    val targetModules = p.gradleModules.filter { it in p.projectTargetNames }.sorted()
    val libraryModules = p.libraries
    t.println("")
    t.println("  " + bold("gradle") + dim("  ·  ${p.gradleModules.size} modules"))

    fun section(label: String, modules: List<String>, note: String) {
        if (modules.isEmpty()) return
        val wName = (modules.maxOf { it.length } + 3).coerceAtLeast(18)
        t.println("")
        t.println("  " + bold(label) + dim("  $note"))
        t.println("      " + dim("module".padEnd(wName)) + dim("show".padEnd(28)) + dim("run task"))
        modules.forEach { module ->
            val show = if (module in p.projectTargetNames) "reaktor $module" else "reaktor $module"
            t.println(
                "      " +
                    bold(module.padEnd(wName)) +
                    dim(show.padEnd(28)) +
                    dim("reaktor gradle $module <task>"),
            )
        }
    }

    section("TARGET MODULES", targetModules, "also appear in targets/")
    section("LIBRARIES", libraryModules, "gradle modules that are not deployment targets")

    t.println("")
    t.println("  " + bold("COMMON TASKS"))
    listOf(
        "reaktor gradle <module> build" to "./gradlew :<module>:build",
        "reaktor gradle <module> test" to "./gradlew :<module>:test",
        "reaktor gradle <module> check" to "./gradlew :<module>:check",
        "reaktor <library> build" to "./gradlew :<library>:build",
        "reaktor gradle tasks" to "./gradlew tasks",
        "reaktor gradle projects" to "./gradlew projects",
    ).forEach { (invoke, backing) ->
        t.println("      " + bold(invoke.padEnd(34)) + dim(backing))
    }
}

/** `reaktor doctor` — environment & project checks. */
class Doctor : CliktCommand() {
    private val env by requireObject<ReaktorEnv>()
    override fun run() {
        val t = env.terminal
        val cwd = File(System.getProperty("user.dir"))
        t.println(bold("reaktor doctor"))
        fun check(label: String, cmd: List<String>) {
            val (code, out) = env.runner.capture(cmd, cwd)
            val line = out.lineSequence()
                .firstOrNull {
                    it.isNotBlank() && !it.startsWith("WARNING") && !it.startsWith("-") && !it.startsWith("npm ")
                }
                ?.take(48).orEmpty()
            t.println("  " + (if (code == 0) green("✓") else red("✗")) + " $label  " + dim(line))
        }
        check("node", listOf("node", "--version"))
        check("npm", listOf("npm", "--version"))
        check("java", listOf("java", "-version"))
        check("gradle", listOf("./gradlew", "--version"))
        check("wrangler", listOf("npx", "wrangler", "--version"))
        val p = env.project
        if (p != null) {
            t.println("  " + green("✓") + " project ${p.name}  " + dim(p.root.path))
            t.println("    " + dim("${p.targets.size} targets · ${p.scripts.size} scripts · ${p.services.size} services · ${p.gradleModules.size} gradle modules"))
        } else {
            t.println("  " + red("✗") + " no reaktor project found (need a package.json with a \"reaktor\" key)")
        }
    }
}

/** Resolve a target by name, or prompt to pick one when omitted. */
private fun resolveTarget(env: ReaktorEnv, p: ReaktorProject, name: String?): Target {
    if (name != null) return p.declaredTargetForName(name) ?: throw UsageError("Unknown target '$name'. Try `reaktor tasks`.")
    val names = p.projectTargetNames.ifEmpty { p.targets.keys.map { p.targetDisplayName(it) } }
    if (names.isEmpty()) throw UsageError("No targets found in targets/ or the package.json \"reaktor\" key")
    if (names.size == 1) return p.declaredTargetForName(names.single())
        ?: throw UsageError("Target '${names.single()}' has no declared action.")
    throw UsageError("Specify a target — one of: ${names.joinToString(", ")}")
}
