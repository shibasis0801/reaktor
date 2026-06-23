package dev.shibasis.reaktor.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

private val accent = cyan
private val muted = TextColors.rgb("#9aa0a6")

private fun surfaceColor(s: String): TextStyle = when (s) {
    "Cloudflare" -> TextColors.rgb("#f6821f")
    "k3s" -> TextColors.rgb("#5b8def")
    "Play Store" -> brightGreen
    "App Store" -> TextColors.rgb("#cbd3df")
    "local" -> TextColors.rgb("#d2a8ff")
    else -> muted
}

/** The bare-`reaktor` dashboard: exact targets, libraries, toolchains, and global operations. */
fun printOverview(env: ReaktorEnv) = renderProject(env, includeCommands = true)

fun renderProject(env: ReaktorEnv, includeCommands: Boolean) {
    val t = env.terminal
    val p = env.project
    t.println("")
    if (p == null) {
        t.println("  " + (bold + accent)("reaktor") + muted("  ·  not in a reaktor project"))
        if (includeCommands) {
            t.println("")
            commands(t)
        }
        return
    }

    t.println("  " + (bold + accent)("reaktor") + muted("  ·  ") + bold(p.name))
    targets(t, p)
    libraries(t, p)
    toolchains(t, p)
    global(t, p, includeCommands)
}

private fun targets(t: Terminal, p: ReaktorProject) {
    val targets = p.projectTargets
    if (targets.isEmpty()) return

    t.println("")
    head(t, "TARGETS", "exact names from targets/   ·   reaktor <target> [action|family]")
    val wName = (targets.maxOf { it.name.length } + 3).coerceAtLeast(16)
    val wKind = 10
    val wSurface = 13
    val stacks = targets.associateWith { targetStack(p, it) }
    val wStack = ((stacks.values.maxOfOrNull { it.length } ?: 20) + 3).coerceAtLeast(23)
    t.println(
        "      " +
            muted("name".padEnd(wName)) +
            muted("kind".padEnd(wKind)) +
            muted("to".padEnd(wSurface)) +
            muted("stack".padEnd(wStack)) +
            muted("commands"),
    )
    targets.forEach { target ->
        val declared = target.declaredName?.let { p.targets[it] }
        val surface = targetSurface(target, declared)
        val stack = stacks.getValue(target)
        val commands = targetCommands(p, target.name)
        t.println(
            "      " +
                bold(target.name.padEnd(wName)) +
                muted(target.kind.padEnd(wKind)) +
                surfaceColor(surface)(surface.padEnd(wSurface)) +
                muted(stack.padEnd(wStack)) +
                muted(commands),
        )
    }
}

private fun libraries(t: Terminal, p: ReaktorProject) {
    val gradleLibraries = p.libraries
    val reaktorModules = p.modules
    if (gradleLibraries.isEmpty() && reaktorModules.isEmpty()) return

    t.println("")
    head(t, "LIBRARIES", "project modules and reaktor modules   ·   reaktor <library>   ·   reaktor gradle <library> <task>")
    if (gradleLibraries.isNotEmpty()) {
        listRow(t, "gradle", gradleLibraries, "Kotlin/Gradle libraries")
    }
    if (reaktorModules.isNotEmpty()) {
        listRow(t, "reaktor", reaktorModules, "framework modules in use")
    }
}

private fun toolchains(t: Terminal, p: ReaktorProject) {
    t.println("")
    head(t, "TOOLCHAINS", "direct tool families   ·   reaktor gradle|fastlane|maestro|karate|k6|dagger|...")
    val rows = mutableListOf<Triple<String, String, String>>()
    if (p.gradleModules.isNotEmpty()) rows += Triple("gradle", "${p.gradleModules.size} modules", "reaktor gradle")
    p.families.forEach { family ->
        val count = p.scripts.keys.count { it == family || it.startsWith("$family:") }
        rows += Triple(family, "$count scripts", "reaktor $family ...")
    }
    if ("dagger" in p.cloud) rows += Triple("dagger", p.cloud.getValue("dagger"), "reaktor dagger")

    val wName = (rows.maxOfOrNull { it.first.length } ?: 8).coerceAtLeast(8) + 3
    val wCount = 13
    rows.distinctBy { it.first }.forEach { (name, count, invoke) ->
        t.println("      " + bold(name.padEnd(wName)) + muted(count.padEnd(wCount)) + muted(invoke))
    }
}

private fun global(t: Terminal, p: ReaktorProject, includeCommands: Boolean) {
    t.println("")
    head(t, "GLOBAL", "project-wide operations")
    if (p.stores.isNotEmpty()) {
        t.println("      " + bold("data".padEnd(10)) + p.stores.joinToString("  ") { bold(it) + muted(" -> " + Topology.store(it)) })
    }
    t.println("      " + bold("scripts".padEnd(10)) + muted("${p.scripts.size} npm scripts   ·   reaktor run [name]"))
    t.println("      " + bold("inspect".padEnd(10)) + muted("reaktor graph   ·   reaktor explain <name>   ·   reaktor tasks"))
    t.println("      " + bold("ops".padEnd(10)) + muted("reaktor engine   ·   reaktor infra   ·   reaktor db   ·   reaktor cloud   ·   reaktor auth"))

    if (includeCommands) {
        t.println("")
        commands(t)
    }
}

private fun targetSurface(target: ProjectTarget, declared: Target?): String {
    if (declared != null) return when (declared.runtime.lowercase()) {
        "android" -> "Play Store"
        "ios" -> "App Store"
        else -> Topology.surface(declared.runtime)
    }
    return when (target.kind) {
        "web", "worker", "service" -> "Cloudflare"
        "server" -> "k3s"
        "mobile" -> if (target.name.contains("Darwin", ignoreCase = true)) "App Store" else "Play Store"
        "desktop" -> "local"
        else -> "local"
    }
}

private fun targetStack(p: ReaktorProject, target: ProjectTarget): String {
    val dir = File(p.root, target.path)
    val stack = linkedSetOf<String>()
    if (target.name in p.gradleModules || File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists()) {
        stack += "gradle"
        stack += "kotlin"
    }
    if (File(dir, "package.json").exists()) stack += "npm"
    if (File(dir, "tsconfig.json").exists() || File(dir, "src/index.ts").exists()) stack += "typescript"
    if (File(dir, "wrangler.json").exists() || File(dir, "wrangler.jsonc").exists()) stack += "wrangler"
    if (File(dir, "CMakeLists.txt").exists()) stack += "cpp"
    if (File(dir, "iosApp.xcworkspace").exists() || File(dir, "iosApp").isDirectory) stack += "xcode"
    return stack.joinToString(" + ").ifEmpty { "files" }
}

private fun targetCommands(p: ReaktorProject, name: String): String {
    val actions = listOfNotNull(
        p.devCommand(name)?.let { "dev" },
        p.buildCommand(name)?.let { "build" },
        p.deployCommand(name)?.let { "deploy" },
        p.targetTestCommand(name)?.let { "test" },
        p.logsCommand(name)?.let { "logs" },
    )
    val npm = p.npmWorkspaceForTarget(name)?.scripts?.takeIf { it.isNotEmpty() }?.let { "npm" }
    val families = p.relatedScriptsForTarget(name)
        .mapNotNull { script ->
            val family = script.substringBefore(':')
            family.takeIf { script.contains(':') && family !in actions }
        }
        .distinct()
        .take(3)
    return (actions + listOfNotNull(npm) + families)
        .joinToString(" · ")
        .ifEmpty { "inspect" }
}

private fun head(t: Terminal, label: String, hint: String) =
    t.println("  " + (bold + accent)(label) + muted("    " + hint))

private fun listRow(t: Terminal, label: String, items: List<String>, hint: String) {
    if (items.isEmpty()) return
    t.println("      " + bold(label.padEnd(10)) + items.joinToString("  ") + muted("   ·   " + hint))
}

private fun commands(t: Terminal) {
    t.println("  " + (bold + accent)("COMMANDS"))
    listOf(
        "targets" to "<target> shows tasks · <target> build|deploy|logs|test · deploy <target>",
        "tools" to "gradle · fastlane · maestro · karate · k6 · dagger · keploy · perf · test",
        "project" to "tasks · run · dev · build · deploy · logs · test",
        "inspect" to "graph · explain",
        "ops" to "engine · infra · db · cloud · auth",
        "make" to "new · add",
        "cli" to "doctor · install · self update|uninstall",
    ).forEach { (g, c) -> t.println("      " + bold(g.padEnd(9)) + "  " + muted(c)) }
    t.println("      " + muted("reaktor <command> --help   ·   reaktor run  (all scripts)"))
}
