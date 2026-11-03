package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ClusterReader(
    private val state: Path = Path.of(System.getProperty("user.home"), "dev", ".remote-dev"),
    private val probe: HostProbe = HostProbe(),
    private val now: () -> Long = System::currentTimeMillis,
    private val alive: (Long) -> Boolean = { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) },
    private val processStart: (Long) -> String? = { pid -> ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null)?.let {
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(it)
    } },
    private val inspect: (Path) -> Lease = NativeLease::inspect,
    private val truthOverride: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun status(): ClusterStatus {
        val at = now()
        val problems = mutableListOf<String>()
        val config = readObject(state.resolve("config.json"), problems)
        runCatching { configuredSlotRoots(state, config ?: JsonObject(emptyMap())) }.onFailure { problems += "Configured slot root is invalid or contains a symlink" }
        val remote = config?.string("host") ?: "m1-worker"
        val hosts = listOf(probe.read("local"), probe.read(remote, remote)).map { host ->
            host.problem?.let { problems += "${host.id}: $it" }
            host.copy(truth = truthOverride ?: host.truth)
        }
        val jobs = jobs(limit = 500)
        problems += jobs.problems
        val slots = slots(config).map { slot -> slot.copy(job = if (slot.lease.state == "Held") jobs.jobs.firstOrNull {
            it.host == slot.host && (it.slot ?: 0) == slot.index && it.state == "Running"
        }?.id else null) }
        val queue = queue(problems)
        val storage = storage(problems)
        return ClusterStatus(at, hosts, slots, queue, storage,
            truthOverride ?: if (problems.isEmpty() && jobs.truth != "Partial" && storage.truth == "Source") "Live" else "Partial", problems.distinct())
    }

    fun jobs(limit: Int = 100, since: Long = now() - 86_400_000): ClusterJobs {
        require(limit in 1..500)
        val problems = mutableListOf<String>()
        val paths = files(state.resolve("logs"), problems, ".json")
        val ordered = paths.sortedByDescending { it.fileName.toString() }
        val records = ordered.mapNotNull { path ->
            val record = readObject(path, problems) ?: return@mapNotNull null
            val started = timestamp(record.string("started"))
            val finished = timestamp(record.string("finished"))
            val owner = record["owner"] as? JsonObject
            val pid = owner?.long("pid")
            val ownerStart = owner?.string("started")
            val exit = record.long("exit_code")?.toInt()
            val status = when {
                finished != null || exit != null -> "Finished"
                pid != null && !alive(pid) -> "Abandoned"
                pid != null && ownerStart != null -> processStart(pid)?.let { if (it == ownerStart) "Running" else "Abandoned" } ?: "Unknown"
                else -> "Unknown"
            }
            if ((finished ?: started)?.let { it < since } == true && status != "Running") return@mapNotNull null
            val tasks = runCatching { record["tasks"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter { it.matches(Regex(":?[A-Za-z_][A-Za-z0-9_.-]*(?::[A-Za-z_][A-Za-z0-9_.-]*)*")) } }.getOrNull().orEmpty()
            val log = path.resolveSibling(path.fileName.toString().removeSuffix(".json") + ".log")
            Job(record.string("run_id") ?: path.fileName.toString().removeSuffix(".json"),
                record.string("project") ?: "Unknown", tasks, status, record.string("worker") ?: "Unknown",
              record.long("worker_slot")?.toInt(), Requester(owner?.string("seat"), owner?.string("plan_item"), pid, owner?.string("session")),
                record.double("queue_seconds")?.let { wait -> started?.minus((wait * 1000).toLong()) }, started, finished,
                record.double("queue_seconds"), exit, path.toString(), log.takeIf(Files::isRegularFile)?.toString(),
                when { record.string("copy_error") != null -> "Failed"; finished == null -> "Pending"; else -> "Complete" },
                truthOverride ?: if (status == "Unknown") "Partial" else "Source")
        }
        val waiting = queue(problems).map { waiter ->
            Job(waiter.id, "Unknown", emptyList(), if (waiter.reason.startsWith("Output")) "Publishing" else "Queued",
                "Unknown", null, Requester(pid = waiter.pid), waiter.queuedAt, null, null,
                waiter.queuedAt?.let { (now() - it).coerceAtLeast(0) / 1000.0 }, null,
                state.resolve("local-gradle.line").resolve(waiter.id).toString(), null, "Pending", truthOverride ?: "Partial")
        }
        val page = waiting + records
        if (page.size > limit) problems += "Job page truncated to $limit of ${page.size} records"
        return ClusterJobs(now(), page.take(limit), truthOverride ?: if (problems.isEmpty() && page.none { it.truth == "Partial" }) "Source" else "Partial", problems)
    }

    private fun slots(config: JsonObject?): List<Slot> {
        val remote = config?.string("host") ?: "m1-worker"
        val root = config?.string("remote_root") ?: "Unknown"
        val count = config?.long("worker_slots")?.toInt()?.coerceIn(1, 3) ?: 2
        val workers = config?.long("worker_slot_max_workers")?.toInt()?.coerceIn(1, 64) ?: 6
        fun slot(host: String, index: Int, parallelism: Int, tree: String, file: String): Slot = Slot(host, index,
            parallelism, tree, inspect(state.resolve(file)).let { it.copy(truth = truthOverride ?: it.truth) })
        return listOf(slot("local", 0, 6, state.parent.toString(), "local-gradle.lock")) +
            (0..2).map { index -> slot(remote, index, if (index == 0) 10 else workers,
                if (index == 0) root else "$root/.remote-dev/slots/job-$index",
                if (index == 0) "worker-gradle.lock" else "worker-slot-$index.lock") }
                .filter { it.index < count || it.lease.state == "Held" }
    }

    private fun queue(problems: MutableList<String>): List<Waiter> = files(state.resolve("local-gradle.line"), problems).mapNotNull { path ->
        val name = path.fileName.toString()
        val match = Regex("(\\d+)-(\\d+)-[A-Za-z0-9]+$").matchEntire(name) ?: return@mapNotNull null
        val pid = match.groupValues[2].toLongOrNull()
        if (pid == null || !alive(pid)) return@mapNotNull null
        val held = inspect(path)
        if (held.state != "Held") return@mapNotNull null
        val aside = runCatching { Files.readString(path).trim() == "aside" }.getOrDefault(false)
        Waiter(name, pid, match.groupValues[1].toLongOrNull()?.div(1_000_000),
            if (aside) "Output publication waits for the local lease" else "Waiting for the local Gradle lease",
            truthOverride ?: "Live")
    }.sortedBy { it.id }

    private fun storage(problems: MutableList<String>): Storage {
        val value = readObject(state.resolve("storage-budget.json"), problems)
            ?: return Storage(emptyList(), null, null, null, truthOverride ?: "Unknown")
        val observed = value.double("finished_epoch")?.times(1000)?.toLong()
        val truth = truthOverride ?: if (observed == null || now() - observed > 600_000) "Stale" else "Source"
        val budgets = listOf("remote", "tmp").map { name ->
            val budget = value[name] as? JsonObject
            StorageBudget(name, budget?.long("limit_bytes"), budget?.long("bytes_after"),
                (budget?.get("within_limit") as? JsonPrimitive)?.booleanOrNull, truth)
        }
        return Storage(budgets, value.long("free_bytes_after"), value.long("minimum_free_bytes"), observed, truth)
    }

    private fun files(directory: Path, problems: MutableList<String>, suffix: String? = null): List<Path> {
        if (!Files.exists(directory)) return emptyList()
        return try {
            Files.list(directory).use { stream ->
                val paths = stream.filter { (suffix == null || it.fileName.toString().endsWith(suffix)) &&
                    Files.isRegularFile(it) && !Files.isSymbolicLink(it) }.limit(20_001).toList()
                if (paths.size > 20_000) problems += "Directory enumeration exceeded 20000 entries; global recency is unknown: ${directory.fileName}"
                if (paths.size > 2000) problems += "Source page limited to the newest 2000 enumerated entries: ${directory.fileName}"
                paths.take(20_000).sortedByDescending { it.fileName.toString() }.take(2000)
            }
        } catch (_: Exception) { problems += "Directory unavailable: ${directory.fileName}"; emptyList() }
    }

    private fun readObject(path: Path, problems: MutableList<String>): JsonObject? {
        if (!Files.exists(path)) { problems += "Source unavailable: ${path.fileName}"; return null }
        return try {
            require(!Files.isSymbolicLink(path) && Files.size(path) <= 1_048_576)
            json.parseToJsonElement(Files.readString(path)) as JsonObject
        } catch (_: Exception) { problems += "Source unreadable: ${path.fileName}"; null }
    }

    private fun timestamp(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrElse { _ ->
            runCatching { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
}
