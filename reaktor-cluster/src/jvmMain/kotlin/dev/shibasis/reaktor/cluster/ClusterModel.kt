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
data class HostProcess(val pid: Long, val name: String, val cpuPercent: Double?, val memoryBytes: Long?)

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
    val host: String, val slot: Int?, val requester: Requester, val queuedAt: Long?, val startedAt: Long?,
    val finishedAt: Long?, val waitSeconds: Double?, val exitCode: Int?, val receipt: String,
    val log: String?, val copyBack: String, val truth: String = "Source")

@Serializable
data class Waiter(val id: String, val pid: Long?, val queuedAt: Long?, val reason: String, val truth: String)

@Serializable
data class StorageBudget(val name: String, val limitBytes: Long?, val bytes: Long?, val withinLimit: Boolean?,
    val truth: String = "Source")

@Serializable
data class Storage(val budgets: List<StorageBudget>, val freeBytes: Long?, val minimumFreeBytes: Long?,
    val observedAt: Long?, val truth: String)

@Serializable
data class ClusterStatus(val observedAt: Long, val hosts: List<Host>, val slots: List<Slot>,
    val queue: List<Waiter>, val storage: Storage, val truth: String, val problems: List<String> = emptyList())

@Serializable
data class ClusterJobs(val observedAt: Long, val jobs: List<Job>, val truth: String, val problems: List<String> = emptyList())

@Serializable
data class ClusterTask(
    val id: String, val title: String, val provider: String, val state: String,
    val host: String?, val startedAt: Long, val updatedAt: Long,
    val workingDirectory: String? = null, val truth: String = "Source",
)

@Serializable
data class ClusterTasks(
    val observedAt: Long, val tasks: List<ClusterTask>, val truth: String,
    val problems: List<String> = emptyList(),
)
