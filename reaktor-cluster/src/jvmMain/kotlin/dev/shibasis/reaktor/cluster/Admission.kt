package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class AdmissionConfig(
    val localSlots: Int = 1,
    val workerSlots: Int = 2,
    val workerHost: String = "m1-worker",
    val localReserveCores: Int = 4,
    val localReserveBytes: Long = 12L * 1_073_741_824,
    val slotMemoryBytes: Long = 8L * 1_073_741_824,
    val minimumDiskBytes: Long = 20L * 1_073_741_824,
    val observationMaxAgeMillis: Long = 30_000,
    val headlessOnWorker: Boolean = false,
) {
    init {
        require(localSlots in 1..2 && workerSlots in 1..3)
        require(workerHost != "local" && workerHost.matches(Regex("[A-Za-z0-9_.-]+")))
        require(localReserveCores >= 0 && localReserveBytes >= 0 && slotMemoryBytes > 0 && minimumDiskBytes >= 0 && observationMaxAgeMillis > 0)
    }
    val localWorkers: Int get() = if (localSlots == 1) 6 else 5
    fun workerParallelism(index: Int, occupied: Boolean): Int = if (index == 0 && !occupied) 10 else if (workerSlots == 3) 4 else 6
}

enum class AdmissionHost { Any, Local, Worker, Device, Headless }

data class AdmissionRequest(
    val id: String,
    val project: String,
    val tasks: List<String>,
    val host: AdmissionHost = AdmissionHost.Any,
    val requester: Requester = Requester(),
    val queuedAt: Long,
    val preferWorker: Boolean = false,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        require(project.isNotBlank() && project.length <= 128 && tasks.isNotEmpty())
        require(tasks.all { it.matches(Regex(":?[A-Za-z_][A-Za-z0-9_.-]*(?::[A-Za-z_][A-Za-z0-9_.-]*)*")) })
    }
}

@Serializable
data class AdmissionRecord(
    val id: String, val project: String, val tasks: List<String>, val host: String, val slot: Int,
    val workers: Int, val requester: Requester, val queuedAt: Long, val admittedAt: Long, val lease: String,
)

sealed interface AdmissionDecision {
    data class Waiting(val reasons: List<String>) : AdmissionDecision
    class Admitted internal constructor(val record: AdmissionRecord, private val release: () -> Unit) : AdmissionDecision, AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
}

class Admission(
    private val state: Path,
    val config: AdmissionConfig = AdmissionConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    private val acquire: (Path) -> AutoCloseable? = { NativeLease.tryAcquire(it) },
    private val record: (AdmissionRecord) -> Unit = { writeAdmission(state.resolve("admissions"), it) },
) {
    private data class Candidate(val host: Host, val index: Int, val path: Path, val workers: Int)

    @Synchronized
    fun admit(request: AdmissionRequest, status: ClusterStatus): AdmissionDecision {
        val marker = state.resolve("maintenance.json")
        if (Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val active = runCatching { !Files.isSymbolicLink(marker) && Json.parseToJsonElement(Files.readString(marker)).jsonObject["active"]?.jsonPrimitive?.booleanOrNull == false }.getOrDefault(false)
            if (!active) return AdmissionDecision.Waiting(listOf("Reviewed maintenance window: new work is not admitted"))
        }
        val at = now()
        if (request.queuedAt > at) return AdmissionDecision.Waiting(listOf("Request is from the future"))
        if (Files.exists(state.resolve("admissions/${request.id}.json"))) return AdmissionDecision.Waiting(listOf("Request already has an admission receipt"))
        val reasons = mutableListOf<String>()
        val hosts = status.hosts.filter { host -> when (request.host) {
            AdmissionHost.Local -> host.id == "local"
            AdmissionHost.Worker, AdmissionHost.Device -> host.id == config.workerHost
            AdmissionHost.Headless -> host.id == "local" || config.headlessOnWorker && host.id == config.workerHost
            AdmissionHost.Any -> host.id in setOf("local", config.workerHost)
        } }
        if (hosts.isEmpty()) return AdmissionDecision.Waiting(listOf("No host satisfies ${request.host}"))
        val candidates = hosts.flatMap { host ->
            val local = host.id == "local"
            val occupied = status.slots.any { it.host == host.id && it.lease.state == "Held" }
            val count = if (local) config.localSlots else config.workerSlots
            (0 until count).filter { request.host != AdmissionHost.Device || it == 0 }.mapNotNull { index ->
                val workers = if (local) config.localWorkers else config.workerParallelism(index, occupied)
                val problem = capacityProblem(host, at, workers)
                if (problem != null) { reasons += "${host.id}/$index: $problem"; null }
                else Candidate(host, index, state.resolve(if (local) {
                    if (index == 0) "local-gradle.lock" else "local-slot-$index.lock"
                } else if (index == 0) "worker-gradle.lock" else "worker-slot-$index.lock"), workers)
            }
        }.sortedWith(compareByDescending<Candidate> {
            val preferred = if (request.preferWorker) it.host.id == config.workerHost else it.host.id == "local"
            (if (preferred) 200.0 else 0.0) + it.host.capacity!!.cpuIdlePercent!! + it.host.capacity.memoryFreePercent!!
        }.thenBy { it.index })
        for (candidate in candidates) {
            val lease = try { acquire(candidate.path) } catch (_: Exception) {
                reasons += "${candidate.host.id}/${candidate.index}: lease unavailable"; continue
            }
            if (lease == null) { reasons += "${candidate.host.id}/${candidate.index}: lease held"; continue }
            try {
                val admitted = AdmissionRecord(request.id, request.project, request.tasks, candidate.host.id, candidate.index,
                    candidate.workers, request.requester, request.queuedAt, at, candidate.path.toString())
                record(admitted)
                return AdmissionDecision.Admitted(admitted) { lease.close() }
            } catch (_: Exception) {
                lease.close()
                return AdmissionDecision.Waiting(listOf("Admission receipt could not be recorded; lease released"))
            }
        }
        return AdmissionDecision.Waiting(reasons.distinct())
    }

    private fun capacityProblem(host: Host, at: Long, workers: Int): String? {
        if (host.truth != "Live" || host.state != "Reachable" || at - host.observedAt !in 0..config.observationMaxAgeMillis) return "Fresh live host capacity is unavailable"
        val capacity = host.capacity ?: return "Capacity is unknown"
        val cores = capacity.cores?.takeIf { it > 0 } ?: return "CPU count is unknown"
        val idle = capacity.cpuIdlePercent?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return "CPU idle is unknown"
        val bytes = capacity.memoryBytes?.takeIf { it > 0 } ?: return "Memory size is unknown"
        val free = capacity.memoryFreePercent?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return "Free memory is unknown"
        if (capacity.pressure != 1) return "Memory pressure is not known to be normal"
        val local = host.id == "local"
        if (local && config.localReserveCores + config.localSlots * config.localWorkers > cores) return "Configured slots exceed the CPU reserve"
        if (cores * idle / 100.0 < minOf(workers, cores)) return "CPU headroom is below the slot budget"
        if (bytes * free / 100.0 < config.slotMemoryBytes + if (local) config.localReserveBytes else 0L) return "Free memory does not cover the reserve and slot budget"
        if (capacity.diskFreeBytes == null || capacity.diskFreeBytes < config.minimumDiskBytes) return "Disk free is below the configured reserve or unknown"
        return null
    }
}

fun requesterIdentity(planItem: String? = null, environment: (String) -> String? = System::getenv, pid: Long = ProcessHandle.current().pid()): Requester {
    fun identity(name: String): String? = environment(name)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
    val codex = identity("CODEX_THREAD_ID") ?: identity("CODEX_SESSION_ID")
    val claude = if (codex == null) identity("CLAUDE_CODE_SESSION_ID") ?: identity("CLAUDE_SESSION_ID") else null
    return Requester(if (codex != null) "Codex" else if (claude != null) "ClaudeCode" else null,
        planItem?.also { require(it.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) }, pid, codex ?: claude)
}

private fun writeAdmission(directory: Path, value: AdmissionRecord) {
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".admit-", ".tmp")
    try {
        Files.writeString(temporary, Json.encodeToString(value))
        Files.move(temporary, directory.resolve("${value.id}.json"), StandardCopyOption.ATOMIC_MOVE)
    } finally { Files.deleteIfExists(temporary) }
}
