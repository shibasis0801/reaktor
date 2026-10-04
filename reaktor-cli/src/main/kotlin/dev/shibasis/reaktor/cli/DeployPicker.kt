package dev.shibasis.reaktor.cli

import java.util.Locale

fun selectDeployTarget(env: ReaktorEnv, project: ReaktorProject, args: List<String>): String =
    selectOption(
        env = env,
        title = "deploy target",
        options = project.deployPickerOptions(args, env.runner::redactedCommand),
        emptyMessage = "No deploy commands found. Add a target deploy, deploy<Name> script, or targets/<name>/deploy.sh.",
    )

fun ReaktorProject.deployPickerOptions(
    args: List<String>,
    renderCommand: (List<String>) -> String = { it.joinToString(" ") },
): List<PickerOption> {
    val names = linkedSetOf<String>()
    names += projectTargetNames
    names += targets.keys.map { targetDisplayName(it) }
    names += deployScriptAliases()

    val seenCommands = linkedSetOf<String>()
    return names.mapNotNull { name ->
        val command = deployCommand(name, args) ?: return@mapNotNull null
        val safeCommand = renderCommand(command.command)
        if (!seenCommands.add(safeCommand)) return@mapNotNull null

        val target = declaredTargetForName(name)
        val projectTarget = projectTarget(name)
        val kind = projectTarget?.kind ?: target?.runtime ?: "service"
        val surface = target?.runtime?.let(Topology::surface)
            ?: projectTarget?.kind
            ?: "script"
        PickerOption(
            value = name,
            label = name,
            detail = "$kind · $surface · $safeCommand",
            keywords = listOfNotNull(
                kind,
                surface,
                safeCommand,
                target?.name,
                target?.workspace,
                target?.workspace?.substringAfterLast('/'),
                projectTarget?.path,
            ) + targetAliases(name, target) + scriptTitleVariants(name),
        )
    }.sortedWith(compareBy<PickerOption> { deploySortBucket(it.label) }.thenBy { it.label.lowercase(Locale.ROOT) })
}

private fun ReaktorProject.deployScriptAliases(): List<String> =
    scripts.keys
        .filter { it.startsWith("deploy") && it.length > "deploy".length }
        .map { it.removePrefix("deploy").replaceFirstChar { char -> char.lowercaseChar() } }

private fun deploySortBucket(name: String): Int = when {
    name.startsWith("app", ignoreCase = true) -> 0
    name.startsWith("reaktor", ignoreCase = true) -> 1
    name.endsWith("Server", ignoreCase = true) -> 2
    name.endsWith("Worker", ignoreCase = true) -> 3
    else -> 4
}
