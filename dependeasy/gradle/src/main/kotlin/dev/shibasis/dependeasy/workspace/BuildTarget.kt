package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.configuration.resolve
import org.gradle.api.Project
import org.gradle.api.provider.Provider

class BuildTarget internal constructor(private val project: Project, val name: String, private val site: SourceSite?) {
    var effect = Effect.Build
    var host = HostRequirement()
    var runner = Runner.Gradle
    var worker: String? = null
    var workerDirectory: String? = null
    val bindings = mutableListOf<String>()
    val environments = mutableListOf<Environment>()
    internal val invocation = mutableListOf<String>()
    private val subsequentCommands = mutableListOf<List<String>>()
    internal val recipes = mutableListOf<String>()
    internal val dependencies = mutableListOf<Any>()
    internal val ordering = mutableListOf<Any>()
    internal val requirements = mutableListOf<Provider<OptionRequirement>>()
    private val edges = mutableListOf<Edge>()
    private val outputs = mutableListOf<OutputHandle>()
    private val includedNodes = mutableListOf<NodeHandle>()
    internal var exposesOutputs = false
    private val id get() = NodeId(project.rootProject.name, project.path, name)

    fun includes(vararg nodes: NodeHandle) {
        nodes.forEach { node ->
            if (node.task != null) dependencies.add(node.task)
            else recipes.add(node.name)
            edges.add(Edge.Includes(id, node.id))
            includedNodes.add(node)
        }
    }

    fun includes(build: BuildHandle, role: Workspace) {
        val output = output(build, role)
        dependencies.add(project.resolve(output))
        edges.add(Edge.Includes(id, output.nodeId))
        outputs.add(output)
    }

    fun consumes(vararg handles: OutputHandle) {
        handles.forEach { output ->
            dependencies.add(project.resolve(output))
            edges.add(Edge.Consumes(id, output.nodeId, output.selector))
            outputs.add(output)
        }
    }

    fun after(vararg nodes: NodeHandle) {
        nodes.forEach { node ->
            require(node.task != null) { "Ordering needs a Gradle node: ${node.id}" }
            ordering.add(node.task)
            edges.add(Edge.After(id, node.id))
        }
    }

    fun <T : Any> require(option: BuildOption<T>, value: T) {
        requirements.add(project.resolve(option).map { OptionRequirement(it.evidence, value.toString()) })
    }

    fun command(vararg arguments: String) {
        require(arguments.isNotEmpty())
        if (invocation.isEmpty()) invocation.addAll(arguments) else subsequentCommands.add(arguments.toList())
    }
    fun pnpm(script: String) { runner = Runner.Pnpm; command("pnpm", "run", script) }
    fun fastlane(lane: LaneHandle) { runner = Runner.Fastlane; command("./fastlane/run.sh", lane.platform, lane.name) }

    internal fun outputKinds(): List<OutputKind> = (outputs.map { it.kind } + includedNodes.flatMap { it.outputKinds() }).distinct()

    internal fun dependencies(): List<Any> {
        requirements.map { it.get() }.forEach { requirement ->
            check(requirement.option.value == requirement.expected) {
                "$name requires ${requirement.option.name}=${requirement.expected}; resolved from ${requirement.option.source}${requirement.option.file?.let { " ($it)" }.orEmpty()}"
            }
        }
        return dependencies
    }

    internal fun model(): Node {
        val kinds = outputKinds()
        val platform = if (kinds.any { it.platform == HostPlatform.Macos }) HostPlatform.Macos else host.platform
        val family = kinds.map { it.family }.distinct().singleOrNull()
        val files = outputs.map { FileSet(project.resolve(it).paths(project), it.kind.name) }
        return Node(id, if (invocation.isEmpty() && recipes.isEmpty()) NodeKind.Target else NodeKind.External,
            name, effect, host.copy(platform = platform), site, kernelRef(project, WorkspaceTargetTask::class.java),
            if (invocation.isEmpty() && recipes.isEmpty()) Invocation.Gradle(listOf(project.path.trimEnd(':') + ":" + name))
            else Invocation.Command((listOf(invocation.toList()) + subsequentCommands).filter { it.isNotEmpty() }, recipes.toList()),
            inputs = files, outputs = if (exposesOutputs) files else emptyList(), runner = runner.name.lowercase(),
            artifactFamily = family, worker = worker, workerDirectory = workerDirectory, bindings = bindings.sorted(),
            environments = environments.map { it.name }, requirements = requirements.map { it.get() })
    }

    internal fun edges(): List<Edge> = edges.distinct()
    internal fun omissions(): List<Omission> = if (site == null) listOf(Omission(id, "DSL call has no build-script stack location")) else emptyList()
}
