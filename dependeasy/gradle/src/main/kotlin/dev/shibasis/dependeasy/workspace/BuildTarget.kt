package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

enum class BuildHost { Local, Worker }

class BuildTarget internal constructor(private val project: Project, val name: String, private val site: SourceSite?) {
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
    private val references = mutableListOf<() -> List<NodeId>>()

    fun modules(vararg lifecycles: String) {
        require(lifecycles.isNotEmpty() && lifecycles.all { it.isNotBlank() && ':' !in it })
        project.subprojects.forEach { child ->
            val selected = child.tasks.matching { it.name in lifecycles }
            dependencies.add(selected)
            references.add { selected.map { NodeId.task(it.project.rootProject.name, it.path) } }
        }
    }

    fun tasks(vararg paths: String) {
        paths.forEach { path ->
            require(path.startsWith(":")) { "Use an absolute Gradle task path: $path" }
            dependencies.add(path)
            references.add { listOf(NodeId.task(project.rootProject.name, path)) }
        }
    }

    fun included(build: String, vararg paths: String) {
        paths.forEach { path ->
            dependencies.add(project.gradle.includedBuild(build).task(path))
            references.add { listOf(NodeId.task(build, path)) }
        }
    }

    fun task(provider: TaskProvider<out Task>) {
        dependencies.add(provider)
        references.add { provider.get().let { listOf(NodeId.task(it.project.rootProject.name, it.path)) } }
    }

    internal fun model(): Node = Node(
        id = NodeId(project.rootProject.name, project.path, name),
        kind = if (invocation.isEmpty() && recipes.isEmpty()) NodeKind.Target else NodeKind.External,
        label = name,
        effect = Effect.entries.single { it.name.equals(effect, ignoreCase = true) },
        host = HostRequirement(HostPlatform.entries.single { it.name.equals(platform, ignoreCase = true) },
            executionHost?.let { ExecutionHost.valueOf(it.name) }),
        declaredAt = site,
        kernel = kernelRef(project, WorkspaceTargetTask::class.java),
        invocation = if (invocation.isEmpty() && recipes.isEmpty()) Invocation.Gradle(listOf(":$name"))
            else Invocation.Command((listOf(invocation.toList()) + subsequentCommands).filter { it.isNotEmpty() }, recipes.toList()),
        outputs = output?.let { listOf(FileSet(listOf(it), artifactFamily)) }.orEmpty(),
        runner = runner, artifactFamily = artifactFamily, worker = worker, workerDirectory = workerDirectory,
        bindings = bindings.sorted(), environments = environments.toList())

    internal fun edges(): List<Edge> = references.flatMap { it() }.distinct().map { Edge.Includes(model().id, it) }
    internal fun omissions(): List<Omission> = edges().map { Omission(model().id, "foreign task path", it.to.toString()) } +
        listOf(Omission(model().id, "Inputs and foreign task outputs are not declared by the current DSL")) +
        if (site == null) listOf(Omission(model().id, "DSL call has no build-script stack location")) else emptyList()
}
