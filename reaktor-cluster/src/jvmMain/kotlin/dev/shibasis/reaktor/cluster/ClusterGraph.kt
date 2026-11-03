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

class ClusterGraph(status: ClusterStatus) : Graph(
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
