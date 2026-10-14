package dev.shibasis.dependeasy.dag

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.workspace.kernelRef
import dev.shibasis.dependeasy.workspace.WorkspaceTargetTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declaredFileInputs

/** Declaration only: Gradle remains the scheduler and incremental build engine. */
class BuildPipeline internal constructor(private val project: Project, val name: String) {
    private val nodes = linkedMapOf<String, BuildNode>()
    private val sites = linkedMapOf<String, SourceSite?>()
    private val targetSites = linkedMapOf<String, SourceSite?>()
    private val consumed = mutableListOf<Triple<BuildNode, BuildNode, BuildArtifact<*>>>()
    private val prerequisites = linkedMapOf<String, MutableSet<String>>()
    private val targets = linkedMapOf<String, List<String>>()

    fun node(task: TaskProvider<out Task>, id: String = task.name): BuildNode {
        val existing = nodes[id]
        require(existing == null || existing.task == task) {
            "Pipeline '$name' already has node '$id'; use a project-qualified ID for foreign tasks"
        }
        return existing ?: BuildNode(task, id, this).also { nodes[id] = it; sites[id] = project.declarationSite() }
    }

    internal fun connect(node: BuildNode, prerequisite: BuildNode) {
        require(nodes[node.id] === node && nodes[prerequisite.id] === prerequisite) {
            "Both nodes must belong to pipeline '$name'"
        }
        val from = node.id
        val to = prerequisite.id
        fun reaches(current: String, target: String, visited: MutableSet<String>): Boolean =
            current == target || (visited.add(current) && prerequisites[current].orEmpty().any {
                reaches(it, target, visited)
            })
        if (reaches(to, from, mutableSetOf())) {
            throw GradleException("Pipeline '$name' has a cycle: $from -> $to -> $from")
        }
        prerequisites.getOrPut(from) { linkedSetOf() }.add(to)
    }

    /** An explicit entrypoint; unrelated backend work is never attached to `help`. */
    fun target(taskName: String, vararg leaves: BuildNode): TaskProvider<out Task> {
        require(leaves.all { nodes[it.id] === it }) { "Target '$taskName' must use nodes from pipeline '$name'" }
        targets[taskName] = leaves.map { it.id }
        targetSites[taskName] = project.declarationSite()
        return project.tasks.register(taskName, WorkspaceTargetTask::class.java) {
            group = "dependeasy"
            description = "Build pipeline ${this@BuildPipeline.name}"
            dependsOn(leaves.map { it.task })
        }
    }

    fun report(taskName: String = "${name}BuildPlan"): TaskProvider<PipelineReport> =
        project.tasks.register(taskName, PipelineReport::class.java) {
            group = "dependeasy"
            description = "Show pipeline ${this@BuildPipeline.name} without running its tools"
            stages.set(describe())
        }.also { BuildWorkspace.get(project).include(this) }

    private fun identity(node: BuildNode): NodeId = node.task.get().let { NodeId.task(it.project.rootProject.name, it.path) }

    internal fun consume(node: BuildNode, producer: BuildNode, artifact: BuildArtifact<*>) {
        consumed.add(Triple(node, producer, artifact))
    }

    internal fun modelNodes(): List<Node> = nodes.values.map { node ->
        val task = node.task.get()
        Node(identity(node), NodeKind.Kernel, node.id, (task as? dev.shibasis.dependeasy.process.KernelSpecOwner)?.kernelEffect ?: Effect.Build, (task as? dev.shibasis.dependeasy.process.KernelSpecOwner)?.kernelHost ?: HostRequirement(), sites[node.id], kernelRef(task),
            Invocation.Gradle(listOf(task.path)),
            inputs = task.declaredFileInputs() + consumed.filter { it.first === node }.map { FileSet(listOf(it.third.location.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)) },
            outputs = listOf(FileSet(task.outputs.files.files.map { it.relativeTo(project.rootDir).invariantSeparatorsPath }.sorted())) + consumed.filter { it.second === node }.map { FileSet(listOf(it.third.location.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)) },
            tools = emptyList())
    } + targets.map { (name, leaves) ->
        val kernels = leaves.mapNotNull { nodes.getValue(it).task.get() as? dev.shibasis.dependeasy.process.KernelSpecOwner }
        val effects = kernels.map { it.kernelEffect }
        val effect = if (Effect.Deploy in effects) Effect.Deploy else if (kernels.size == leaves.size && effects.all { it == Effect.Verify }) Effect.Verify else Effect.Build
        val platforms = kernels.map { it.kernelHost.platform }.filter { it != HostPlatform.Portable }.distinct()
        require(platforms.size <= 1) { "Pipeline target $name requires incompatible host platforms" }
        Node(NodeId(project.rootProject.name, project.path, name), NodeKind.Target, name,
        effect, HostRequirement(platforms.singleOrNull() ?: HostPlatform.Portable), targetSites[name], kernelRef(project, WorkspaceTargetTask::class.java),
        Invocation.Gradle(listOf(project.path.trimEnd(':') + ":" + name))) }

    internal fun modelEdges(): List<Edge> = nodes.values.flatMap { node ->
        prerequisites[node.id].orEmpty().map { Edge.After(identity(node), identity(nodes.getValue(it))) }
    } + consumed.map { (node, producer, artifact) -> Edge.Consumes(identity(node), identity(producer), artifact.id) } +
        targets.flatMap { (name, leaves) -> leaves.map { Edge.Includes(NodeId(project.rootProject.name, project.path, name), identity(nodes.getValue(it))) } }

    internal fun modelOmissions(): List<Omission> = modelNodes().flatMap { node ->
        listOfNotNull(if (node.declaredAt == null) Omission(node.id, "DSL call has no build-script stack location") else null,
            if (node.kernel?.source == null) Omission(node.id, "Foreign kernel source is unavailable") else null,
            if (node.kind == NodeKind.Kernel && node.kernel?.spec == null) Omission(node.id, "Foreign task inputs, effect and host requirements await typed kernels") else null)
    }

    fun describe(): List<String> = nodes.keys.map { task ->
        "$task <- ${prerequisites[task].orEmpty().joinToString(", ")}".trimEnd()
    }
}
