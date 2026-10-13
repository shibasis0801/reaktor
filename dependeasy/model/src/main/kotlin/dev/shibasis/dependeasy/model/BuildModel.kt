package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class NodeId(val build: String, val project: String, val name: String) {
    init {
        require(build.isNotBlank() && ':' !in build)
        require(project.startsWith(":") && (project == ":" || !project.endsWith(":")))
        require(name.isNotBlank() && ':' !in name)
    }
    override fun toString() = build + project.trimEnd(':') + ":" + name
    companion object {
        fun parse(value: String): NodeId {
            val parts = value.split(':')
            require(parts.size >= 2)
            return NodeId(parts.first(), if (parts.size == 2) ":" else ":" + parts.drop(1).dropLast(1).joinToString(":"), parts.last())
        }
        fun task(build: String, path: String): NodeId = parse(build + path)
    }
}

@Serializable
data class BuildModel(
    val schema: Int = 2,
    val producer: Producer,
    val builds: List<BuildIdentity>,
    val declarations: List<DeclarationInput> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val omissions: List<Omission> = emptyList(),
)

@Serializable data class Producer(val name: String = "dependeasy", val version: String)
@Serializable data class BuildIdentity(val name: String, val directory: String)
@Serializable data class DeclarationInput(val build: String, val path: String, val sha256: String)
@Serializable data class SourceSite(val build: String, val file: String, val line: Int) {
    init { require(line > 0 && file.isNotBlank()) }
}
@Serializable data class KernelRef(val className: String, val source: SourceSite? = null)
@Serializable enum class NodeKind { Target, Kernel, External }
@Serializable enum class Effect { Verify, Build, Develop, Deploy }
@Serializable enum class HostPlatform { Portable, Macos, Linux }
@Serializable enum class ExecutionHost { Local, Worker }
@Serializable data class HostRequirement(val platform: HostPlatform = HostPlatform.Portable, val execution: ExecutionHost? = null)

@Serializable
data class Node(
    val id: NodeId,
    val kind: NodeKind,
    val label: String,
    val effect: Effect,
    val host: HostRequirement,
    val declaredAt: SourceSite?,
    val kernel: KernelRef?,
    val invocation: Invocation,
    val inputs: List<FileSet> = emptyList(),
    val outputs: List<FileSet> = emptyList(),
    val tools: List<ToolRef> = emptyList(),
    val additionalDeclarations: List<SourceSite> = emptyList(),
    val runner: String = "gradle",
    val artifactFamily: String? = null,
    val worker: String? = null,
    val workerDirectory: String? = null,
    val bindings: List<String> = emptyList(),
    val environments: List<String> = emptyList(),
)

@Serializable
sealed interface Invocation {
    @Serializable data class Gradle(val tasks: List<String>) : Invocation
    @Serializable data class Command(val commands: List<List<String>>, val recipes: List<String> = emptyList()) : Invocation
}

@Serializable data class FileSet(val paths: List<String>, val kind: String? = null)
@Serializable data class ToolRef(val name: String, val version: String)
@Serializable data class Omission(val node: NodeId?, val reason: String, val reference: String? = null)

@Serializable
sealed interface Edge {
    val from: NodeId
    val to: NodeId
    @Serializable data class Includes(override val from: NodeId, override val to: NodeId) : Edge
    @Serializable data class Consumes(override val from: NodeId, override val to: NodeId, val output: String) : Edge
    @Serializable data class After(override val from: NodeId, override val to: NodeId) : Edge
}

@Serializable
data class RunReceipt(
    val run: String,
    val node: NodeId,
    val startedAt: String,
    val finishedAt: String?,
    val status: String,
    val tasks: List<TaskObservation> = emptyList(),
)
@Serializable data class TaskObservation(val task: String, val outcome: String)

@Serializable data class RemoteHostsContract(val schema: Int = 2, val builds: Map<String, String>, val taskFamilies: Map<String, String>)
