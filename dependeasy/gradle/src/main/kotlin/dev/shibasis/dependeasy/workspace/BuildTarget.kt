package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.*
import org.gradle.api.Project

internal class BuildTarget(private val project: Project, val name: String, private val site: SourceSite?, private val kernel: WorkspaceKernel) {
    var effect = Effect.Build
    var host = HostRequirement()
    var runner = Runner.Gradle
    var worker: WorkerRef? = null
    val bindings = mutableListOf<ServiceRef>()
    val environments = mutableListOf<Environment>()
    internal val dependencies = mutableListOf<Any>()
    internal val ordering = mutableListOf<NodeHandle>()
    private val edges = mutableListOf<Edge>()
    private val outputs = mutableListOf<OutputHandle>()
    internal val consumedOutputs get() = outputs.toList()
    internal val includedNodes = mutableListOf<NodeHandle>()
    internal var exposesOutputs = false
    internal var declaration: KernelSpec.WorkspaceNode? = null
    internal val id get() = NodeId(project.rootProject.name, project.path, name)

    fun includes(vararg nodes: NodeHandle) {
        nodes.forEach { node ->
            dependencies.add(requireNotNull(node.task) { "A dependency needs a declared kernel: ${node.id}" })
            edges.add(Edge.Includes(id, node.id))
            includedNodes.add(node)
            declaration = declaration?.let { it.copy(includes = (it.includes + node.id).distinct()) }
        }
    }

    fun consumes(vararg handles: OutputHandle) {
        handles.forEach { output ->
            dependencies.add(project.resolve(output))
            edges.add(Edge.Consumes(id, output.nodeId, output.selector))
            outputs.add(output)
            declaration = declaration?.let { it.copy(consumes = (it.consumes + output.nodeId).distinct()) }
        }
    }

    fun after(vararg nodes: NodeHandle) {
        nodes.forEach { node ->
            require(node.task != null) { "Ordering needs a Gradle node: ${node.id}" }
            dependencies.add(node.task)
            require(node.id.build == id.build) { "Use declared outputs across builds" }
            ordering.add(node)
            edges.add(Edge.After(id, node.id))
            declaration = declaration?.let { it.copy(after = (it.after + node.id).distinct()) }
        }
    }

    internal fun outputKinds(): List<OutputKind> = (outputs.map { it.kind } + includedNodes.flatMap { it.outputKinds() }).distinct()

    internal fun dependencies(): List<Any> {
        kernel.validate(requireNotNull(declaration))
        return dependencies
    }

    internal fun model(consumer: Project): Node {
        val kinds = outputKinds()
        val platform = if (kinds.any { it.platform == HostPlatform.Macos }) HostPlatform.Macos else host.platform
        val family = kinds.map { it.family }.distinct().singleOrNull()
        val files = outputs.map { FileSet(consumer.resolve(it).paths(consumer), it.kind.name) }
        return Node(id, requireNotNull(declaration).kind,
            name, effect, host.copy(platform = platform), site,
            kernelRef(project, kernel.javaClass).copy(spec = declaration),
            Invocation.Gradle(listOf(project.path.trimEnd(':') + ":" + name)),
            inputs = files, outputs = if (exposesOutputs) files else emptyList(), runner = runner,
            artifactFamily = family, worker = worker, bindings = bindings.sortedBy { it.name },
            environments = environments.toList(), requirements = requireNotNull(declaration).requirements)
    }

    internal fun edges(): List<Edge> = edges.distinct()
    internal fun omissions(): List<Omission> = if (site == null) listOf(Omission(id, "DSL call has no build-script stack location")) else emptyList()
}
