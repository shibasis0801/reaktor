package dev.shibasis.reaktor.cli

import java.io.File

data class ProjectCommand(
    val label: String,
    val command: List<String>,
    val cwd: File,
)

private fun scriptTitle(name: String): String =
    name.replaceFirstChar { it.uppercase() }

fun ReaktorProject.scriptTitleVariants(name: String): List<String> {
    val out = linkedSetOf<String>()
    fun add(value: String) {
        if (value.isNotBlank()) out += scriptTitle(value)
    }

    add(name)
    val withoutServer = name.removeSuffix("Server")
    val withoutWorker = name.removeSuffix("Worker")
    add(withoutServer)
    add(withoutWorker)

    val prefix = namePrefix()
    if (name.startsWith(prefix, ignoreCase = true) && name.length > prefix.length) {
        val withoutProjectPrefix = name.drop(prefix.length).replaceFirstChar { it.lowercase() }
        add(withoutProjectPrefix)
        add(withoutProjectPrefix.removeSuffix("Server"))
        add(withoutProjectPrefix.removeSuffix("Worker"))
    }

    val declared = declaredTargetForName(name)
    if (declared != null) {
        add(declared.name)
        declared.workspace?.substringAfterLast('/')?.let(::add)
    }

    return out.toList()
}

private fun ReaktorProject.npm(script: String, args: List<String> = emptyList()): ProjectCommand =
    ProjectCommand(
        label = if (args.isEmpty()) "npm run $script" else "npm run $script -- ${args.joinToString(" ")}",
        command = listOf("npm", "run", script) + if (args.isEmpty()) emptyList() else listOf("--") + args,
        cwd = root,
    )

private fun ReaktorProject.gradle(args: List<String>): ProjectCommand =
    ProjectCommand(
        label = "./gradlew ${args.joinToString(" ")}",
        command = listOf("./gradlew") + args,
        cwd = root,
    )

private fun ReaktorProject.shell(path: String, args: List<String> = emptyList()): ProjectCommand =
    ProjectCommand(
        label = path + if (args.isEmpty()) "" else " ${args.joinToString(" ")}",
        command = listOf("bash", path) + args,
        cwd = root,
    )

fun ReaktorProject.directScriptCommand(name: String, args: List<String> = emptyList()): ProjectCommand? =
    if (name in scripts) npm(name, args) else null

fun ReaktorProject.workspaceScriptCommand(
    target: String,
    script: String,
    args: List<String> = emptyList(),
): ProjectCommand? {
    val workspace = npmWorkspaceForTarget(target) ?: return null
    if (script !in workspace.scripts) return null
    return ProjectCommand(
        label = if (args.isEmpty()) {
            "npm run $script --workspace=${workspace.name}"
        } else {
            "npm run $script --workspace=${workspace.name} -- ${args.joinToString(" ")}"
        },
        command = listOf("npm", "run", script, "--workspace=${workspace.name}") +
            if (args.isEmpty()) emptyList() else listOf("--") + args,
        cwd = root,
    )
}

fun ReaktorProject.devCommand(target: String, args: List<String> = emptyList()): ProjectCommand? {
    val t = declaredTargetForName(target) ?: return null
    return t.dev?.let { npm(it, args) }
}

fun ReaktorProject.buildCommand(name: String, args: List<String> = emptyList()): ProjectCommand? {
    val t = declaredTargetForName(name)
    if (t != null) {
        return when {
            t.gradle != null -> gradle(listOf(t.gradle) + args)
            t.build != null -> npm(t.build, args)
            else -> null
        }
    }
    if (name in gradleModules) {
        val task = args.firstOrNull() ?: "build"
        val rest = if (args.isEmpty()) emptyList() else args.drop(1)
        return gradle(listOf(":$name:$task") + rest)
    }
    return null
}

fun ReaktorProject.deployCommand(name: String, args: List<String> = emptyList()): ProjectCommand? {
    val t = declaredTargetForName(name)
    val declared = t?.deploy
    if (declared != null) return npm(declared, args)

    val exactDeploy = "deploy${scriptTitle(name)}"
    if (exactDeploy in scripts) return npm(exactDeploy, args)

    val targetDeploy = File(root, "targets/$name/deploy.sh")
    if (targetDeploy.exists()) return shell("targets/$name/deploy.sh", args)

    for (title in scriptTitleVariants(name).drop(1)) {
        val npmDeploy = "deploy$title"
        if (npmDeploy in scripts) return npm(npmDeploy, args)
    }

    return null
}

fun ReaktorProject.logsCommand(name: String, args: List<String> = emptyList()): ProjectCommand? {
    val exactLogs = "logs${scriptTitle(name)}"
    if (exactLogs in scripts) return npm(exactLogs, args)

    val deployment = deploymentNameFromTargetDeploy(name)
    if (deployment != null) {
        val kubeconfig = File(root, "cloud/k3s/kubeconfig")
        return ProjectCommand(
            label = "kubectl logs deployment/$deployment",
            command = listOf(
                "kubectl",
                "--kubeconfig",
                kubeconfig.absolutePath,
                "logs",
                "-n",
                "reaktor",
                "deployment/$deployment",
                "--tail=100",
                "-f",
            ) + args,
            cwd = root,
        )
    }

    for (title in scriptTitleVariants(name).drop(1)) {
        val npmLogs = "logs$title"
        if (npmLogs in scripts) return npm(npmLogs, args)
    }

    return null
}

private fun ReaktorProject.deploymentNameFromTargetDeploy(name: String): String? {
    val deploy = File(root, "targets/$name/deploy.sh").takeIf { it.exists() } ?: return null
    return Regex("""deployment/([A-Za-z0-9_.-]+)""")
        .find(deploy.readText())
        ?.groupValues
        ?.getOrNull(1)
}

fun ReaktorProject.testCommand(path: List<String>, args: List<String> = emptyList()): ProjectCommand? {
    if (path.isEmpty()) return null

    val name = path.first()
    val t = declaredTargetForName(name)
    if (t?.test != null && path.size == 1) return npm(t.test, args)

    val colonScript = "test:" + path.joinToString(":")
    if (colonScript in scripts) return npm(colonScript, args)

    if (name in gradleModules) {
        val task = path.getOrNull(1) ?: "test"
        return gradle(listOf(":$name:$task") + path.drop(2) + args)
    }

    return null
}

fun ReaktorProject.storeConsoleCommand(store: String, args: List<String> = emptyList()): ProjectCommand? =
    if (store in scripts) npm(store, args) else null

fun ReaktorEnv.run(command: ProjectCommand): Int =
    runner.run(command.command, command.cwd)
