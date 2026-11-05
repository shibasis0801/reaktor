package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.graph.core.Graph
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

    val processes = status.hosts.flatMap { host -> host.processes?.processes.orEmpty().map { process ->
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

class HostNode(graph: Graph, val snapshot: Host) : BasicNode(graph) {
    override val id = ClusterGraph.identity("cluster/host/${snapshot.id}")
    override val label = snapshot.id
    val provider = registerProvider("host", snapshot)
}

class SlotNode(graph: Graph, val snapshot: Slot) : BasicNode(graph) {
    override val id = ClusterGraph.identity("cluster/host/${snapshot.host}/slot/${snapshot.index}")
    override val label = "${snapshot.host} slot ${snapshot.index}"
    val host = registerConsumer<Host>("host")
}

class ProcessNode(graph: Graph, hostId: String, val snapshot: HostProcess) : BasicNode(graph) {
    override val id = ClusterGraph.identity("cluster/host/$hostId/process/${snapshot.pid}")
    override val label = "${snapshot.name} (${snapshot.pid})"
    val host = registerConsumer<Host>("host")
}

class JobNode(graph: Graph, val snapshot: Job) : BasicNode(graph) {
    override val id = ClusterGraph.identity("cluster/job/${snapshot.id}")
    override val label = "${snapshot.project}: ${snapshot.tasks.joinToString()}"
    val host = registerConsumer<Host>("host")
}

class TaskNode(graph: Graph, val snapshot: ClusterTask) : BasicNode(graph) {
    override val id = ClusterGraph.identity("cluster/task/${snapshot.id}")
    override val label = snapshot.title
    val host = registerConsumer<Host>("host")
}
