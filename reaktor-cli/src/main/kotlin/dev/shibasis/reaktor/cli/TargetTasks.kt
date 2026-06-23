package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import java.util.Locale

private data class TaskRow(
    val invoke: String,
    val source: String,
    val backing: String,
)

private data class TaskSection(
    val name: String,
    val rows: List<TaskRow>,
)

fun ReaktorProject.targetTestCommand(name: String, args: List<String> = emptyList()): ProjectCommand? {
    val target = declaredTargetForName(name)
    if (args.isEmpty() && target?.test != null) return directScriptCommand(target.test)
    return targetFamilyCommand(name, "test", args)
        ?: testCommand(listOf(name) + args)
}

fun ReaktorProject.targetFamilyCommand(name: String, family: String, path: List<String> = emptyList()): ProjectCommand? {
    val target = declaredTargetForName(name)
    val familyScripts = scripts.keys.filter { it == family || it.startsWith("$family:") }.toSet()
    if (familyScripts.isEmpty()) return null

    val attempts = mutableListOf<List<String>>()
    if (path.isNotEmpty()) attempts += path
    targetAliases(name, target).forEach { alias -> attempts += listOf(alias) + path }

    for (segments in attempts.distinctBy { it.joinToString(":").lowercase(Locale.ROOT) }) {
        val resolved = resolveFamilyScript(family, segments, familyScripts) ?: continue
        return directScriptCommand(resolved.script, resolved.args)
    }
    return null
}

fun renderTargetDashboard(env: ReaktorEnv, name: String, kind: String = "target") {
    val p = env.requireProject()
    val target = p.declaredTargetForName(name)
    val projectTarget = p.projectTarget(name)
    if (kind == "target" && target == null && projectTarget == null) throw UsageError("Unknown target '$name'.")

    val t = env.terminal
    val heading = if (projectTarget != null || target != null) {
        "target $name"
    } else {
        "$kind $name"
    }
    t.println("")
    val surface = target?.let { dim("  ${it.runtime} -> ${Topology.surface(it.runtime)}") }
        ?: projectTarget?.let { dim("  ${it.kind}") }
        ?: ""
    t.println("  " + bold(heading) + surface)
    val path = projectTarget?.path ?: target?.workspace
    path?.let { t.println("  " + dim("workspace: $it")) }

    val sections = p.taskSectionsFor(name, target)
    if (sections.isEmpty()) {
        t.println("  " + dim("no tasks resolved"))
        return
    }
    sections.forEach { renderSection(env, it) }
}

fun renderGradleModuleDashboard(env: ReaktorEnv, module: String) {
    val p = env.requireProject()
    if (module !in p.gradleModules) throw UsageError("No gradle module '$module'.")
    val t = env.terminal
    t.println("")
    t.println("  " + bold("gradle module $module"))
    renderSection(
        env,
        TaskSection(
            "gradle",
            listOf(
                TaskRow("reaktor $module build", "gradle", "./gradlew :$module:build"),
                TaskRow("reaktor $module test", "gradle", "./gradlew :$module:test"),
                TaskRow("reaktor $module check", "gradle", "./gradlew :$module:check"),
                TaskRow("reaktor gradle $module <task>", "gradle", "./gradlew :$module:<task>"),
            ),
        ),
    )
}

fun renderTargetFamily(env: ReaktorEnv, name: String, family: String) {
    val p = env.requireProject()
    val target = p.declaredTargetForName(name)
    val rows = p.relatedScriptsForTarget(name, target)
        .filter { it == family || it.startsWith("$family:") }
        .map { script ->
            TaskRow(
                invoke = p.targetInvokeForScript(name, target, script),
                source = "npm",
                backing = "npm run $script",
            )
        }
    if (rows.isEmpty()) throw UsageError("No $family tasks resolved for '$name'.")
    renderSection(env, TaskSection(family, rows))
}

private fun ReaktorProject.taskSectionsFor(name: String, target: Target?): List<TaskSection> {
    val sections = mutableListOf<TaskSection>()

    val actions = listOf("dev", "build", "deploy", "test", "logs").mapNotNull { action ->
        val command = targetActionCommand(name, action) ?: return@mapNotNull null
        TaskRow("reaktor $name $action", action, command.label)
    }
    if (actions.isNotEmpty()) sections += TaskSection("actions", actions)

    val workspace = npmWorkspaceForTarget(name)
    if (workspace != null && workspace.scripts.isNotEmpty()) {
        val npmRows = workspace.scripts.keys.sorted().map { script ->
            TaskRow(
                "reaktor $name npm $script",
                "npm",
                "npm run $script --workspace=${workspace.name}",
            )
        }
        sections += TaskSection("npm", npmRows)
    }

    val gradleRows = relatedGradleModules(name, target).flatMap { module ->
        listOf(
            TaskRow("reaktor $module", "gradle", "./gradlew :$module:build"),
            TaskRow("reaktor $module test", "gradle", "./gradlew :$module:test"),
            TaskRow("reaktor gradle $module <task>", "gradle", "./gradlew :$module:<task>"),
        )
    }
    if (gradleRows.isNotEmpty()) sections += TaskSection("gradle", gradleRows)

    val actionInvokes = actions.map { it.invoke }.toSet()
    val scriptRows = relatedScriptsForTarget(name, target)
        .filter { it.contains(':') }
        .filterNot { targetInvokeForScript(name, target, it) in actionInvokes }
        .map { script ->
            val family = script.substringBefore(':')
            TaskRow(
                invoke = targetInvokeForScript(name, target, script),
                source = family,
                backing = "npm run $script",
            )
        }
    scriptRows.groupBy { it.source }.toSortedMap().forEach { (family, rows) ->
        sections += TaskSection(family, rows.sortedBy { it.invoke })
    }

    return sections
}

private fun ReaktorProject.targetActionCommand(name: String, action: String): ProjectCommand? = when (action) {
    "dev" -> devCommand(name)
    "build" -> buildCommand(name)
    "deploy" -> deployCommand(name)
    "test" -> targetTestCommand(name)
    "logs" -> logsCommand(name)
    else -> null
}

fun ReaktorProject.relatedScriptsForTarget(name: String, target: Target? = declaredTargetForName(name)): List<String> {
    val declared = listOfNotNull(target?.dev, target?.build, target?.deploy, target?.test).toSet()
    val aliases = targetAliases(name, target).map { it.lowercase(Locale.ROOT) }.toSet()
    return scripts.keys
        .filter { script ->
            script in declared || scriptMatchesAliases(script, aliases)
        }
        .sorted()
}

fun ReaktorProject.targetAliases(name: String, target: Target? = declaredTargetForName(name)): List<String> {
    val out = linkedSetOf<String>()
    fun add(value: String?) {
        val v = value?.trim()
        if (!v.isNullOrBlank()) out += v
    }

    add(name)
    add(target?.name)
    add(target?.workspace?.substringAfterLast('/'))
    scriptTitleVariants(name).forEach { title ->
        add(title.replaceFirstChar { it.lowercaseChar() })
    }

    val runtime = target?.runtime?.lowercase(Locale.ROOT)
    val project = namePrefix()
    if (runtime == "android" || name.equals("android", ignoreCase = true)) {
        add("android")
        add("appAndroid")
        add("${project}Android")
    }
    if (runtime == "ios" || name.equals("ios", ignoreCase = true)) {
        add("ios")
        add("darwin")
        add("${project}Darwin")
        add("${project}Ios")
    }
    if (name.endsWith("Web") || runtime == "js-web" || runtime == "web") {
        add(name)
        if (name.equals("appWeb", ignoreCase = true) || target?.dev == "web") {
            add("web")
            add("${project}Web")
        }
    }

    return out.distinctBy { it.lowercase(Locale.ROOT) }
}

private fun ReaktorProject.relatedGradleModules(name: String, target: Target?): List<String> {
    val aliases = targetAliases(name, target).map { it.lowercase(Locale.ROOT) }.toSet()
    val runtime = target?.runtime?.lowercase(Locale.ROOT)
    return gradleModules.filter { module ->
        val lower = module.lowercase(Locale.ROOT)
        lower in aliases ||
            aliases.any { lower == it || lower.endsWith(it) } ||
            (runtime == "android" && lower.contains("android")) ||
            (name.endsWith("Server") && lower == name.lowercase(Locale.ROOT))
    }.distinct().sorted()
}

private fun ReaktorProject.targetInvokeForScript(name: String, target: Target?, script: String): String {
    if (!script.contains(':')) return "reaktor run $script"

    val aliases = targetAliases(name, target).map { it.lowercase(Locale.ROOT) }.toSet()
    val parts = script.split(':')
    val family = parts.first()
    val rest = parts.drop(1)
    val withoutTarget = if (rest.firstOrNull()?.lowercase(Locale.ROOT) in aliases) rest.drop(1) else rest
    if (withoutTarget.isEmpty()) return "reaktor $family ${rest.joinToString(" ")}"
    return listOf("reaktor", name, family)
        .plus(withoutTarget)
        .joinToString(" ")
}

private fun scriptMatchesAliases(script: String, aliases: Set<String>): Boolean {
    if (!script.contains(':')) return script.lowercase(Locale.ROOT) in aliases
    return script.split(':').any { it.lowercase(Locale.ROOT) in aliases }
}

private data class ResolvedFamilyScript(val script: String, val args: List<String>)

private fun resolveFamilyScript(family: String, path: List<String>, scripts: Set<String>): ResolvedFamilyScript? {
    val parts = listOf(family) + path
    return parts.indices
        .map { index ->
            val script = parts.take(index + 1).joinToString(":")
            ResolvedFamilyScript(script, parts.drop(index + 1))
        }
        .lastOrNull { it.script in scripts }
}

fun ReaktorProject.namePrefix(): String =
    name.split('-', '_', '.', '/').firstOrNull()?.takeIf { it.isNotBlank() } ?: name

private fun renderSection(env: ReaktorEnv, section: TaskSection) {
    val t = env.terminal
    val accent = TextColors.cyan
    val width = section.rows.maxOf { it.invoke.length }.coerceAtMost(42) + 3
    t.println("")
    t.println("  " + (bold + accent)(section.name))
    section.rows.forEach { row ->
        val invoke = row.invoke.padEnd(maxOf(width, row.invoke.length + 2))
        t.println(
            "    " +
                bold(invoke) +
                dim(row.backing),
        )
    }
}
