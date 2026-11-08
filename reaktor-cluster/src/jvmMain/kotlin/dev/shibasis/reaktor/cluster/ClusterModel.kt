package dev.shibasis.reaktor.cluster

import kotlinx.serialization.Serializable

@Serializable
data class Capacity(
    val cores: Int? = null,
    val memoryBytes: Long? = null,
    val cpuIdlePercent: Double? = null,
    val memoryFreePercent: Double? = null,
    val pressure: Int? = null,
    val compressedBytes: Long? = null,
    val swapBytes: Long? = null,
    val diskFreeBytes: Long? = null,
    val load: Double? = null,
)

@Serializable
data class Host(val id: String, val ssh: String? = null, val roles: List<String>, val state: String,
    val capacity: Capacity? = null, val observedAt: Long, val truth: String, val problem: String? = null,
    val processes: HostProcesses? = null)

@Serializable
data class HostProcess(val pid: Long, val name: String, val cpuPercent: Double? = null, val memoryBytes: Long? = null, val role: String? = null)

val HostProcess.roleLabel: String? get() = role?.let(::componentName)

fun componentName(role: String): String = when (role) {
    "hangar-host" -> "Hangar host"
    "hangar-window" -> "Hangar window"
    "mcp-relay" -> "MCP relay"
    "kotlin-daemon" -> "Kotlin daemon"
    "gradle-daemon" -> "Gradle daemon"
    "gradle-client" -> "Gradle client"
    "idb-companion" -> "iPhone forwarder"
    "typescript-indexer" -> "TypeScript indexer"
    "tooling:remote-dev" -> "Build router"
    "tooling:dev-storage" -> "Storage agent"
    else -> role.removePrefix("tooling:")
}

@Serializable
data class HostProcesses(val observedAt: Long, val processes: List<HostProcess>, val truth: String, val problem: String? = null)

@Serializable
data class Lease(val path: String, val state: String, val pid: Long? = null, val truth: String = "Live")

@Serializable
data class Slot(val host: String, val index: Int, val workers: Int, val tree: String, val lease: Lease, val job: String? = null)

@Serializable
data class Requester(val seat: String? = null, val planItem: String? = null, val pid: Long? = null, val session: String? = null)

@Serializable
data class Job(val id: String, val project: String, val tasks: List<String>, val state: String,
    val host: String, val slot: Int? = null, val requester: Requester, val queuedAt: Long? = null, val startedAt: Long? = null,
    val finishedAt: Long? = null, val waitSeconds: Double? = null, val exitCode: Int? = null, val receipt: String,
    val log: String? = null, val copyBack: String, val truth: String = "Source",
    val phase: String = "Unknown", val executionHost: String? = null, val phaseHost: String? = null,
    val blocker: JobBlocker? = null)

@Serializable
data class JobBlocker(val kind: String, val reason: String, val host: String? = null, val jobId: String? = null)

@Serializable
data class Waiter(val id: String, val pid: Long? = null, val queuedAt: Long? = null, val reason: String, val truth: String)

@Serializable
data class StorageBudget(val name: String, val limitBytes: Long? = null, val bytes: Long? = null, val withinLimit: Boolean? = null,
    val truth: String = "Source")

@Serializable
data class Storage(val budgets: List<StorageBudget>, val freeBytes: Long? = null, val minimumFreeBytes: Long? = null,
    val observedAt: Long? = null, val truth: String)

@Serializable
data class ClusterStatus(val observedAt: Long, val hosts: List<Host>, val slots: List<Slot>,
    val queue: List<Waiter>, val storage: Storage, val truth: String, val problems: List<String> = emptyList())

@Serializable
data class ClusterJobs(val observedAt: Long, val jobs: List<Job>, val truth: String, val problems: List<String> = emptyList())

@Serializable
data class ClusterTask(
    val id: String, val title: String, val provider: String, val state: String,
    val host: String? = null, val startedAt: Long, val updatedAt: Long,
    val workingDirectory: String? = null, val truth: String = "Source",
    val pendingCount: Int = 0, val lastTool: String? = null, val failure: String? = null,
    val revision: Long = 0, val recovery: String? = null,
    val workflow: ClusterTaskWorkflow? = null,
    val workspaceRoot: String? = null, val workspaceKey: String? = null,
    val ownerIncarnation: String? = null,
)

@Serializable
data class ClusterTaskWorkflow(
    val id: String, val title: String,
    val stages: List<ClusterTaskStage>, val edges: List<ClusterTaskEdge>,
)

@Serializable
data class ClusterTaskStage(
    val id: String, val title: String, val action: String,
    val agent: String? = null, val state: String? = null,
)

@Serializable
data class ClusterTaskEdge(val from: String, val to: String, val condition: String)

@Serializable
data class ClusterTasks(
    val observedAt: Long, val tasks: List<ClusterTask>, val truth: String,
    val problems: List<String> = emptyList(),
)
