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
    val schema: Int = 3,
    val producer: Producer,
    val builds: List<BuildIdentity>,
    val declarations: List<DeclarationInput> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val omissions: List<Omission> = emptyList(),
)

@Serializable data class Producer(val name: String = "dependeasy", val version: String)
@Serializable data class BuildIdentity(val name: String, val directory: String)
@Serializable data class DeclarationInput(val build: String, val path: String, val sha256: String, val kind: DeclarationInputKind = DeclarationInputKind.File, val option: OptionValue? = null)
@Serializable enum class DeclarationInputKind { File, Membership, Option }
@Serializable data class SourceSite(
    val build: String,
    val file: String,
    val line: Int,
    val symbol: String? = null,
    val callPath: List<SourceSite> = emptyList(),
) {
    init { require(line > 0 && file.isNotBlank()) }
}
@Serializable data class KernelRef(val className: String, val source: SourceSite? = null, val spec: KernelSpec? = null)
@Serializable enum class NodeKind { Target, Kernel, Verification, ArtifactSet, Release, Deploy, External }
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
    val runner: Runner = Runner.Gradle,
    val artifactFamily: String? = null,
    val worker: WorkerRef? = null,
    val bindings: List<ServiceRef> = emptyList(),
    val environments: List<Environment> = emptyList(),
    val requirements: List<OptionRequirement> = emptyList(),
)

@Serializable
sealed interface Invocation {
    @Serializable data class Gradle(val tasks: List<String>) : Invocation
    @Serializable data class Command(val commands: List<List<String>>, val recipes: List<String> = emptyList()) : Invocation
}

@Serializable data class FileSet(val paths: List<String>, val kind: String? = null, val declarations: List<FileInput> = emptyList())
@Serializable data class FileInput(val kind: FileInputKind, val identity: String, val includes: List<String> = emptyList(), val excludes: List<String> = emptyList())
@Serializable enum class FileInputKind { File, Tree, Configuration, Producer, Provider, Collection }
@Serializable data class ToolRef(val name: String, val version: String)
@Serializable data class Omission(val node: NodeId?, val reason: String, val reference: String? = null)

@Serializable
sealed interface Edge {
    val from: NodeId
    val to: NodeId
    @Serializable data class Includes(override val from: NodeId, override val to: NodeId) : Edge
    @Serializable data class Consumes(override val from: NodeId, override val to: NodeId, val output: String) : Edge
    @Serializable data class After(override val from: NodeId, override val to: NodeId) : Edge
    @Serializable data class OrderedAfter(override val from: NodeId, override val to: NodeId) : Edge
}

@Serializable
data class RunReceipt(
    val schema: Int = 1,
    val run: String,
    val build: String,
    val requestedNodes: List<NodeId>,
    val requestedTasks: List<String>,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val status: ReceiptStatus,
    val tasks: List<TaskObservation> = emptyList(),
    val dryRun: Boolean = false,
    val modelDigest: String? = null,
    val modelOmission: String? = null,
    val failures: List<String> = emptyList(),
    val requestDigest: String? = null,
    val operationRunId: String? = null,
    val omissionCounts: Map<String, Int> = emptyMap(),
)
@Serializable enum class ReceiptStatus { Succeeded, Failed }
@Serializable enum class TaskOutcome { Executed, UpToDate, FromCache, Skipped, Failed }
@Serializable data class TaskObservation(val task: NodeId, val outcome: TaskOutcome, val startedAtEpochMillis: Long,
    val durationMillis: Long, val failures: List<String> = emptyList())

@Serializable data class RemoteHostsContract(val schema: Int = 2, val builds: Map<String, String>, val taskFamilies: Map<String, String>,
    val taskHosts: Map<String, HostRequirement> = emptyMap(),
    val publicOutputs: Map<String, List<String>> = emptyMap(),
    val kotlinExports: List<String> = emptyList(),
    val environment: List<String> = emptyList(),
    val outputRoots: Map<String, List<String>> = mapOf(
        "all" to listOf("outputs", "libs", "distributions", "reports", "test-results", "bin", "xcode-frameworks", "XCFrameworks", "compose", "processedResources", "classes", "resources", "agent-dist"),
        "verification" to listOf("reports", "test-results", "hangar-flows", "hangar-shards"),
        "android" to listOf("outputs", "libs", "reports", "test-results"),
        "jvm" to listOf("agent-dist", "compose", "distributions", "reports", "test-results"),
        "apple" to listOf("libs", "reports", "test-results", "bin", "xcode-frameworks", "XCFrameworks", "compose", "processedResources"),
        "agent" to listOf("agent-dist"), "metadata" to emptyList(), "web" to listOf("reports", "test-results")),
    val xcodeEnvironment: List<String> = listOf("SDK_NAME", "CONFIGURATION", "ARCHS", "CURRENT_ARCH", "TARGET_BUILD_DIR", "FRAMEWORKS_FOLDER_PATH",
        "DEVELOPER_DIR", "PLATFORM_NAME", "BUILT_PRODUCTS_DIR", "UNLOCALIZED_RESOURCES_FOLDER_PATH", "EXPANDED_CODE_SIGN_IDENTITY",
        "CODE_SIGNING_ALLOWED", "KOTLIN_FRAMEWORK_BUILD_TYPE", "ACTION", "DWARF_DSYM_FOLDER_PATH",
        "ENABLE_USER_SCRIPT_SANDBOXING", "PROJECT_FILE_PATH"))

fun BuildModel.requiredHost(id: NodeId): HostRequirement {
    val byId = nodes.associateBy { it.id }
    val prerequisites = edges.filter { it !is Edge.OrderedAfter }.groupBy { it.from }
    fun resolve(node: NodeId, visited: Set<NodeId>): HostRequirement {
        val own = byId[node]?.host ?: HostRequirement()
        if (node in visited) return own
        val inherited = prerequisites[node].orEmpty().map { resolve(it.to, visited + node) }
        return HostRequirement(
            if (own.platform == HostPlatform.Macos || inherited.any { it.platform == HostPlatform.Macos }) HostPlatform.Macos else own.platform,
            if (own.execution == ExecutionHost.Local || inherited.any { it.execution == ExecutionHost.Local }) ExecutionHost.Local else own.execution,
        )
    }
    return resolve(id, emptySet())
}
