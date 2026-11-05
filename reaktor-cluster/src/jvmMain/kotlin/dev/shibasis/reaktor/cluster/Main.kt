package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.core.build.Entry
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entry
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "status"
    require(command in setOf("status", "jobs", "hygiene", "storage")) { "Usage: reaktor-cluster status|jobs|hygiene|storage [--json] [--state PATH]" }
    val stateIndex = args.indexOf("--state")
    val state = if (stateIndex >= 0) Path.of(requireNotNull(args.getOrNull(stateIndex + 1))) else ClusterLocations.state()
    val reader = ClusterReader(state)
    val json = Json { prettyPrint = true; encodeDefaults = true }
    when (command) {
        "status" -> {
            val status = ClusterRuntime(reader).use { runtime -> runBlocking { runtime.api.status(Unit) } }
            if ("--json" in args) println(json.encodeToString(status)) else {
                for (host in status.hosts) println("${host.id}: ${host.state}, CPU idle ${host.capacity?.cpuIdlePercent ?: "Unknown"}%, free memory ${host.capacity?.memoryFreePercent ?: "Unknown"}%, ${host.truth}")
                for (slot in status.slots) println("${slot.host}/${slot.index}: ${slot.lease.state}, ${slot.job ?: "no matched receipt"}")
                println("${status.queue.size} queued; storage ${status.storage.truth}; status ${status.truth}")
                status.problems.forEach { println(it) }
            }
        }
        "jobs" -> println(json.encodeToString(ClusterRuntime(reader).use { runtime -> runBlocking { runtime.api.jobs(ClusterJobsQuery()) } }))
        "storage" -> {
            require("--apply" !in args) { "Storage is read-only; eviction remains with dev-storage" }
            fun value(flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { requireNotNull(args.getOrNull(it + 1)) }
            val localWorktrees = value("--local-worktrees")?.let { text ->
                require(text.length <= 1_048_576)
                json.decodeFromString<Map<String, Boolean>>(text).also { rows -> require(rows.keys.all { it.matches(Regex("[0-9a-f]{64}")) }) }
            }.orEmpty()
            val source = NativeStorage(state, localWorktrees = localWorktrees)
            val report = if ("--local-only" in args) source.read(value("--host") ?: "local").let { StorageReport(it.observedAt, listOf(it), it.truth) }
                else source.cluster(value("--worker-cli"))
            if ("--no-record" !in args) StorageStore(state.resolve("storage")).write(report)
            println(json.encodeToString(report))
        }
        "hygiene" -> {
            val store = HygieneStore(state.resolve("hygiene"))
            val hygiene = Hygiene(NativeHygiene(state))
            if ("--apply" in args) {
                val index = args.indexOf("--reviewed")
                require(index >= 0) { "Apply requires the reviewed report digest" }
                val reviewed = requireNotNull(store.read().report) { "Record a report before applying" }
                println(json.encodeToString(hygiene.apply(reviewed, requireNotNull(args.getOrNull(index + 1)), state.resolve("hygiene/receipts"))))
            } else {
                val report = hygiene.report(if ("--local-only" in args) listOf("local") else listOf("local", "m1-worker"), store.read().report)
                store.write(report)
                println(json.encodeToString(report))
            }
        }
    }
}
