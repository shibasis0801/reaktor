package dev.shibasis.reaktor.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A declared target — maps a reaktor runtime concept onto the project's existing scripts/gradle tasks. */
data class Target(
    val name: String,
    val runtime: String = "unknown",
    val workspace: String? = null,
    val gradle: String? = null,
    val dev: String? = null,
    val build: String? = null,
    val deploy: String? = null,
    val test: String? = null,
)

/** A concrete directory under `targets/`, with any legacy declaration it maps to. */
data class ProjectTarget(
    val name: String,
    val path: String,
    val kind: String,
    val declaredName: String? = null,
)

data class NpmWorkspace(
    val name: String,
    val path: String,
    val scripts: Map<String, String>,
)

/**
 * The current reaktor project, discovered from files the project already has — never a new
 * config file. The marker is a `package.json` with a top-level `"reaktor"` key; the CLI reuses
 * that file's `scripts` and `workspaces`, the declared `reaktor.targets`, and the gradle
 * `settings.gradle.kts` modules.
 */
class ReaktorProject(
    val root: File,
    val name: String,
    val targets: Map<String, Target>,
    val scripts: Map<String, String>,
    val workspaces: List<String>,
    val gradleModules: List<String>,
    val modules: List<String> = emptyList(),
    val stores: List<String> = emptyList(),
    val cloud: Map<String, String> = emptyMap(),
) {
    val services: List<String> get() = workspaces.map { it.substringAfterLast('/') }

    /** Concrete target folders are the target source of truth for display and shortcuts. */
    val projectTargets: List<ProjectTarget>
        get() = targetFolderNames().map { name ->
            val declaredName = declaredTargetNameForFolder(name)
            ProjectTarget(
                name = name,
                path = "targets/$name",
                kind = inferTargetKind(name, declaredName?.let { targets[it] }),
                declaredName = declaredName,
            )
        }

    val projectTargetNames: List<String> get() = projectTargets.map { it.name }

    val libraries: List<String>
        get() = gradleModules.filter { it !in projectTargetNames }.sorted()

    /** Deployable worker/service target folders, using exact names from `targets/`. */
    val workers: List<String>
        get() = projectTargets.filter { it.kind == "worker" || it.kind == "service" }.map { it.name }

    /** Server target folders, using exact names from `targets/`. */
    val servers: List<String> get() = projectTargets.filter { it.kind == "server" }.map { it.name }

    /** Colon-namespaced script families (fastlane, maestro, test, perf, …). */
    val families: List<String>
        get() = scripts.keys.mapNotNull { it.substringBefore(':', "").ifEmpty { null } }.distinct().sorted()

    fun projectTarget(name: String): ProjectTarget? =
        projectTargets.firstOrNull { it.name == name }

    fun declaredTargetForName(name: String): Target? =
        targets[name] ?: projectTarget(name)?.declaredName?.let { targets[it] }

    fun targetDisplayName(targetName: String): String =
        targets[targetName]?.workspace?.substringAfterLast('/') ?: when (targetName) {
            "android" -> "appAndroid"
            "ios" -> "appDarwin"
            else -> targetName
        }

    fun npmWorkspaceForTarget(name: String): NpmWorkspace? {
        val projectTarget = projectTarget(name) ?: return null
        val pkg = File(root, "${projectTarget.path}/package.json").takeIf { it.exists() } ?: return null
        val json = runCatching { Json.parseToJsonElement(pkg.readText()).jsonObject }.getOrNull() ?: return null
        val packageName = json["name"]?.jsonPrimitive?.contentOrNull ?: name
        val scripts = (json["scripts"] as? JsonObject)
            ?.mapValues { it.value.jsonPrimitive.content }
            .orEmpty()
        return NpmWorkspace(packageName, projectTarget.path, scripts)
    }

    private fun targetFolderNames(): List<String> {
        val fromWorkspaces = workspaces
            .filter { it.startsWith("targets/") }
            .map { it.substringAfterLast('/') }
        val fromDeclared = targets.values
            .mapNotNull { it.workspace?.takeIf { path -> path.startsWith("targets/") }?.substringAfterLast('/') }
        val fromGradle = gradleModules
            .filter { File(root, "targets/$it").isDirectory }
        val fromFilesystem = File(root, "targets")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
        return (fromWorkspaces + fromDeclared + fromGradle + fromFilesystem)
            .distinct()
            .sortedWith(compareBy<String> { targetSortBucket(it) }.thenBy { it.lowercase() })
    }

    private fun declaredTargetNameForFolder(name: String): String? {
        if (name in targets) return name
        targets.values.firstOrNull { it.workspace?.substringAfterLast('/') == name }?.let { return it.name }
        return when {
            name.equals("appAndroid", ignoreCase = true) -> "android".takeIf { it in targets }
            name.equals("appDarwin", ignoreCase = true) -> "ios".takeIf { it in targets }
            name.equals("appIos", ignoreCase = true) -> "ios".takeIf { it in targets }
            else -> null
        }
    }

    private fun inferTargetKind(name: String, declared: Target?): String {
        val runtime = declared?.runtime?.lowercase().orEmpty()
        val dir = File(root, "targets/$name")
        val hasWrangler = File(dir, "wrangler.json").exists() || File(dir, "wrangler.jsonc").exists()
        return when {
            runtime == "android" || runtime == "ios" -> "mobile"
            runtime == "js-web" || runtime == "web" -> "web"
            runtime == "worker" -> "worker"
            runtime == "server" || runtime == "jvm" || runtime == "k3s" -> "server"
            name in gradleModules && name.endsWith("Server", ignoreCase = true) -> "server"
            name.endsWith("Android", ignoreCase = true) -> "mobile"
            name.endsWith("Darwin", ignoreCase = true) || name.endsWith("Ios", ignoreCase = true) -> "mobile"
            name.endsWith("Web", ignoreCase = true) -> "web"
            name.endsWith("Worker", ignoreCase = true) -> "worker"
            hasWrangler -> "service"
            name.endsWith("Server", ignoreCase = true) -> "service"
            name.endsWith("Desktop", ignoreCase = true) -> "desktop"
            else -> "target"
        }
    }

    private fun targetSortBucket(name: String): Int = when {
        name.startsWith("app", ignoreCase = true) -> 0
        name.startsWith("reaktor", ignoreCase = true) -> 1
        name.endsWith("Server", ignoreCase = true) -> 2
        name.endsWith("Worker", ignoreCase = true) -> 3
        else -> 4
    }

    companion object {
        fun discover(start: File = File(System.getProperty("user.dir"))): ReaktorProject? {
            var dir: File? = start.absoluteFile
            while (dir != null) {
                val pkg = File(dir, "package.json")
                if (pkg.exists()) {
                    val json = runCatching { Json.parseToJsonElement(pkg.readText()).jsonObject }.getOrNull()
                    if (json != null && json.containsKey("reaktor")) return load(dir, json)
                }
                dir = dir.parentFile
            }
            return null
        }

        private fun load(root: File, pkg: JsonObject): ReaktorProject {
            val reaktor = (pkg["reaktor"] as? JsonObject) ?: JsonObject(emptyMap())
            val scripts = (pkg["scripts"] as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val workspaces = (pkg["workspaces"] as? JsonArray)
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            val targets = (reaktor["targets"] as? JsonObject)?.mapValues { (name, el) ->
                val o = el.jsonObject
                Target(
                    name = name,
                    runtime = o["runtime"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    workspace = o["workspace"]?.jsonPrimitive?.contentOrNull,
                    gradle = o["gradle"]?.jsonPrimitive?.contentOrNull,
                    dev = o["dev"]?.jsonPrimitive?.contentOrNull,
                    build = o["build"]?.jsonPrimitive?.contentOrNull,
                    deploy = o["deploy"]?.jsonPrimitive?.contentOrNull,
                    test = o["test"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyMap()
            val name = reaktor["name"]?.jsonPrimitive?.contentOrNull
                ?: pkg["name"]?.jsonPrimitive?.contentOrNull
                ?: root.name
            val modules = (reaktor["modules"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val stores = (reaktor["stores"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val cloud = (reaktor["cloud"] as? JsonObject)
                ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
            return ReaktorProject(
                root, name, targets, scripts, workspaces, parseGradleModules(root), modules, stores, cloud,
            )
        }

        private fun parseGradleModules(root: File): List<String> {
            val settings = File(root, "settings.gradle.kts").takeIf { it.exists() }
                ?: File(root, "settings.gradle").takeIf { it.exists() }
                ?: return emptyList()
            val text = settings.readText()
            val modules = mutableListOf<String>()
            // standard: include(":a", ":b")
            Regex("""\binclude\(([^)]*)\)""").findAll(text).forEach { m ->
                Regex(""""(:[^"]+)"""").findAll(m.groupValues[1]).forEach { modules += it.groupValues[1].trimStart(':') }
            }
            // dependeasy: includeWithPath("name", "path")
            Regex("""includeWithPath\(\s*"([^"]+)"""").findAll(text).forEach { modules += it.groupValues[1] }
            return modules.distinct()
        }
    }
}
