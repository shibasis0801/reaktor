package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

enum class BuildHost { Local, Worker }

class BuildTarget internal constructor(private val project: Project, val name: String) {
    var platform = "portable"
    var effect = "build"
    var runner = "gradle"
    var output: String? = null
    var worker: String? = null
    var workerDirectory: String? = null
    var executionHost: BuildHost? = null
    var artifactFamily: String? = null
        set(value) {
            require(value == null || value in setOf("jvm", "web", "android", "apple", "verification"))
            field = value
            value?.let { project.declareArtifactFamily(name, it) }
        }
    val bindings = mutableListOf<String>()
    val environments = mutableListOf<String>()
    fun macos() { platform = "macos" }
    internal val invocation = mutableListOf<String>()
    private val subsequentCommands = mutableListOf<List<String>>()
    internal val recipes = mutableListOf<String>()
    fun recipe(vararg names: String) { recipes.addAll(names); tasks(*names.map { ":$it" }.toTypedArray()) }
    fun command(vararg arguments: String) {
        require(arguments.isNotEmpty())
        if (invocation.isEmpty()) invocation.addAll(arguments) else subsequentCommands.add(arguments.toList())
    }
    fun gradle(vararg paths: String) { tasks(*paths); command("./gradlew", *paths, "--console=plain") }
    fun pnpm(script: String) = command("pnpm", "run", script)
    fun fastlane(platform: String, lane: String) { runner = "fastlane"; command("./fastlane/run.sh", platform, lane) }
    internal val dependencies = mutableListOf<Any>()
    private val references = mutableListOf<() -> List<JsonObject>>()

    fun modules(vararg lifecycles: String) {
        require(lifecycles.isNotEmpty() && lifecycles.all { it.isNotBlank() && ':' !in it }) {
            "Use module lifecycle names, such as build or check"
        }
        project.subprojects.forEach { child ->
            val selected = child.tasks.matching { it.name in lifecycles }
            dependencies.add(selected)
            references.add { selected.map(::reference) }
        }
    }

    fun tasks(vararg paths: String) {
        paths.forEach { path ->
            require(path.startsWith(":")) { "Use an absolute Gradle task path: $path" }
            dependencies.add(path)
            references.add { listOf(reference(project.rootProject.name, path)) }
        }
    }

    fun included(build: String, vararg paths: String) {
        paths.forEach { path ->
            dependencies.add(project.gradle.includedBuild(build).task(path))
            references.add { listOf(reference(build, path)) }
        }
    }

    fun task(provider: TaskProvider<out Task>) {
        dependencies.add(provider)
        references.add { listOf(reference(provider.get())) }
    }

    internal fun json(): String = buildJsonObject {
        put("id", "${project.rootProject.name}:${name}")
        put("kind", if (invocation.isEmpty() && recipes.isEmpty()) "gradle" else "external")
        putJsonArray("recipes") { recipes.forEach { add(it) } }
        putJsonArray("command") { invocation.forEach { add(it) } }
        putJsonArray("commands") { (listOf(invocation.toList()) + subsequentCommands).filter { it.isNotEmpty() }.forEach { command ->
            add(buildJsonArray { command.forEach { add(it) } })
        } }
        put("task", ":$name")
        put("platform", platform)
        put("effect", effect)
        put("runner", runner)
        executionHost?.let { put("executionHost", it.name.lowercase()) }
        artifactFamily?.let { put("artifactFamily", it) }
        output?.let { put("output", it) }
        worker?.let { put("worker", it) }
        workerDirectory?.let { put("workerDirectory", it) }
        putJsonArray("bindings") { bindings.sorted().forEach { add(it) } }
        putJsonArray("environments") { environments.forEach { add(it) } }
        putJsonArray("requires") { references.flatMap { it() }.forEach { add(it) } }
    }.toString()

    private fun reference(task: Task) = reference(task.project.rootProject.name, task.path)

    private fun reference(build: String, path: String) = buildJsonObject {
        put("build", build); put("task", path)
    }
}
