package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import java.security.MessageDigest
import org.koin.dsl.koinApplication
import kotlin.uuid.Uuid

class ClusterGraph(status: ClusterStatus, jobs: ClusterJobs? = null, tasks: ClusterTasks? = null) : Graph(
    id = identity("cluster"), label = "Cluster", dependencyAdapter = KoinDependencyAdapter(koinApplication {}),
) {
    val hosts = status.hosts.associate { host ->
        host.id to Node { graph -> HostNode(graph, host) }
    }
    val slots = status.slots.map { slot ->
        Node { graph -> SlotNode(graph, slot) }.also { node ->
            hosts[slot.host]?.let { host -> connect(node.host, host.provider).getOrThrow() }
        }
    }

    val processes = status.hosts.flatMap { host -> host.processes?.processes.orEmpty().filter { it.role != null }.map { process ->
        Node { graph -> ProcessNode(graph, host.id, process) }.also { node ->
            hosts[host.id]?.let { owner -> connect(node.host, owner.provider).getOrThrow() }
        }
    } }
    val jobs = jobs?.jobs.orEmpty().map { job ->
        Node { graph -> JobNode(graph, job) }.also { node ->
            hosts[job.host]?.let { host -> connect(node.host, host.provider).getOrThrow() }
        }
    }

    val tasks = tasks?.tasks.orEmpty().map { task ->
        Node { graph -> TaskNode(graph, task) }.also { node ->
            task.host?.let(hosts::get)?.let { host -> connect(node.host, host.provider).getOrThrow() }
        }
    }

    companion object {
        internal fun identity(value: String): Uuid = Uuid.fromByteArray(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).copyOf(16))
    }
}

class HostNode(graph: Graph, val snapshot: Host) : BasicNode(graph), ShapeAttributes {
    override val id = ClusterGraph.identity("cluster/host/${snapshot.id}")
    override val label = snapshot.id
    val provider = registerProvider("host", snapshot)
    override val shapeAttributes get() = facts("state" to snapshot.state, "roles" to snapshot.roles.joinToString(), "truth" to snapshot.truth,
        "cores" to snapshot.capacity?.cores?.toString(), "memory" to snapshot.capacity?.memoryBytes?.let(::size),
        "memoryFree" to snapshot.capacity?.memoryFreePercent?.let { "${it.toInt()} %" }, "swap" to snapshot.capacity?.swapBytes?.let(::size),
        "load" to snapshot.capacity?.load?.toString(), "problem" to snapshot.problem)
}

class SlotNode(graph: Graph, val snapshot: Slot) : BasicNode(graph), ShapeAttributes {
    override val id = ClusterGraph.identity("cluster/host/${snapshot.host}/slot/${snapshot.index}")
    override val label = "${snapshot.host} slot ${snapshot.index}"
    val host = registerConsumer<Host>("host")
    override val shapeAttributes get() = facts("lease" to snapshot.lease.state, "workers" to snapshot.workers.toString(),
        "tree" to snapshot.tree, "job" to snapshot.job)
}

class ProcessNode(graph: Graph, hostId: String, val snapshot: HostProcess) : BasicNode(graph), ShapeAttributes {
    override val id = ClusterGraph.identity("cluster/host/$hostId/process/${snapshot.pid}")
    override val label = "${snapshot.roleLabel ?: snapshot.name} (${snapshot.pid})"
    val host = registerConsumer<Host>("host")
    override val shapeAttributes get() = facts("pid" to snapshot.pid.toString(), "component" to snapshot.roleLabel, "executable" to snapshot.name,
        "cpu" to snapshot.cpuPercent?.let { "$it %" }, "memory" to snapshot.memoryBytes?.let(::size))
}

class JobNode(graph: Graph, val snapshot: Job) : BasicNode(graph), ShapeAttributes {
    override val id = ClusterGraph.identity("cluster/job/${snapshot.id}")
    override val label = snapshot.tasks.joinToString().ifEmpty { "${snapshot.state} build" }
        .let { work -> if (snapshot.project == "Unknown") work else "${snapshot.project}: $work" }
    val host = registerConsumer<Host>("host")
    override val shapeAttributes get() = facts("state" to snapshot.state, "phase" to snapshot.phase, "slot" to snapshot.slot?.toString(),
        "seat" to snapshot.requester.seat, "planItem" to snapshot.requester.planItem, "executionHost" to snapshot.executionHost,
        "blocker" to snapshot.blocker?.reason)
}

class TaskNode(graph: Graph, val snapshot: ClusterTask) : BasicNode(graph), ShapeAttributes {
    override val id = ClusterGraph.identity("cluster/task/${snapshot.id}")
    override val label = snapshot.title
    val host = registerConsumer<Host>("host")
    override val shapeAttributes get() = facts("state" to snapshot.state, "provider" to snapshot.provider, "workspace" to snapshot.workspaceRoot,
        "lastTool" to snapshot.lastTool, "failure" to snapshot.failure)
}

private fun facts(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()

private fun size(bytes: Long): String = if (bytes >= 1L shl 30) String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0) else "${bytes shr 20} MB"
