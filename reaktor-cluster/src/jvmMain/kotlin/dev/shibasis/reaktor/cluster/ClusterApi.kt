package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.consumes
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.dsl.koinApplication

@Serializable
data class ClusterJobsQuery(val states: Set<String> = emptySet(), val since: Long? = null, val limit: Int = 100)

open class ClusterApi : Service("reaktor.cluster") {
    open val status by call<Unit, ClusterStatus>(effect = Effect.LiveRead,
        summary = "Observe configured hosts, measured capacity, bounded process metrics, router leases and storage.")
    open val devices by call<Unit, ClusterDevices>(effect = Effect.LiveRead,
        summary = "Observe devices on the configured worker, preserving transport states and cached Apple records.")
    open val jobs by call<ClusterJobsQuery, ClusterJobs>(effect = Effect.ReadOnly,
        summary = "Read routed jobs and their recorded requester, host, slot and outcome.")
    open val tasks by call<Unit, ClusterTasks>(effect = Effect.ReadOnly,
        summary = "Read native Reaktor task summaries with machine attribution proven by the owner.")
    open val inspectJob by call<ClusterJobRequest, ClusterJobControl>(effect = Effect.LiveRead,
        summary = "Inspect proven controls for one exact routed build execution.")
    open val stopJob by call<ClusterJobMutation, ClusterJobControl>(effect = Effect.LocalEphemeral,
        summary = "Request the owned build supervisor to stop the selected execution; older receipts without identity are unsupported.")
    open val resumeJob by call<ClusterJobMutation, ClusterJobControl>(effect = Effect.LocalEphemeral,
        summary = "Recover a routed build only when its control protocol proves that capability.")
    open val settings by call<Unit, ClusterBuildSettings>(effect = Effect.ReadOnly,
        summary = "Read revisioned build concurrency settings used by newly launched routers.")
    open val updateSettings by call<ClusterBuildSettingsUpdate, ClusterBuildSettings>(effect = Effect.NonProductionWrite,
        summary = "Update reviewed build concurrency settings at the expected revision; existing routers keep their loaded policy.")
}

class ClusterRuntime(
    reader: ClusterReader = ClusterReader(),
    readTasks: suspend () -> ClusterTasks = {
        ClusterTasks(System.currentTimeMillis(), emptyList(), "Unknown", listOf("Native task source unavailable"))
    },
    settingsStore: ClusterSettings = ClusterSettings(reader.state),
    controls: ClusterControls = ClusterControls(reader.state),
    deviceReader: ClusterDeviceReader = ClusterDeviceReader(reader.state),
) : AutoCloseable {
    val graph = Graph(label = "Cluster", dependencyAdapter = KoinDependencyAdapter(koinApplication {}))
    private val server = object : ClusterApi() {
        override val status = super.status.handle { withContext(Dispatchers.IO) { reader.status() } }
        override val devices = super.devices.handle { withContext(Dispatchers.IO) { deviceReader.read() } }
        override val tasks = super.tasks.handle { withContext(Dispatchers.IO) { readTasks() } }
        override val inspectJob = super.inspectJob.handle { request -> withContext(Dispatchers.IO) { controls.inspect(request) } }
        override val stopJob = super.stopJob.handle { request -> withContext(Dispatchers.IO) { controls.stop(request) } }
        override val resumeJob = super.resumeJob.handle { request -> withContext(Dispatchers.IO) { controls.resume(request) } }
        override val settings = super.settings.handle { withContext(Dispatchers.IO) { settingsStore.read() } }
        override val updateSettings = super.updateSettings.handle { request -> withContext(Dispatchers.IO) { settingsStore.update(request) } }
        override val jobs = super.jobs.handle { request ->
            require(request.limit in 1..500) { "limit must be between 1 and 500" }
            require(request.since == null || request.since >= 0) { "since must be a nonnegative millisecond timestamp" }
            require(request.states.all { it in setOf("Running", "Queued", "Finished", "Cancelled", "Abandoned", "LeaseHeld", "Unknown", "Publishing") }) { "Unknown job state" }
            withContext(Dispatchers.IO) {
                val source = if (request.since == null) reader.jobs(500) else reader.jobs(500, request.since)
                val matching = source.jobs.filter { request.states.isEmpty() || it.state in request.states }
                val truncated = matching.size > request.limit
                source.copy(jobs = matching.take(request.limit),
                    truth = if (source.truth == "Fixture") source.truth else if (truncated) "Partial" else source.truth,
                    problems = source.problems + if (truncated) listOf("Filtered job page limited to ${request.limit} of ${matching.size} available rows") else emptyList())
            }
        }
    }
    val served = graph.serve(server)
    private val client = graph.Node { ClusterClient(it) }
    init { graph.autoWire() }
    val api: ClusterApi get() = client.api
    override fun close() { graph.close() }
}

private class ClusterClient(graph: Graph) : BasicNode(graph) {
    val api by consumes(ClusterApi())
}
