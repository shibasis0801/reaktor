package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.flow.graph.adapter.shortType
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.flattenedValues

/** References in one graph projection/session, not source IDs across unrelated revisions. */
sealed interface ReaktorGraphSelection {
    data class Element(val id: String) : ReaktorGraphSelection
    data class Port(val nodeId: String, val handleId: String, val direction: ReaktorPortDirection) : ReaktorGraphSelection
    data class Connection(val id: String) : ReaktorGraphSelection
}

enum class ReaktorPortDirection { Provider, Consumer }

/** Membership in the displayed projection, including expanded scope regions and exact pins. */
fun ReaktorFlowGraph.containsSelection(selection: ReaktorGraphSelection): Boolean = when (selection) {
    is ReaktorGraphSelection.Element -> nodes.any { it.id == selection.id } || regions.any { it.id == selection.id }
    is ReaktorGraphSelection.Connection -> edges.any { it.id == selection.id }
    is ReaktorGraphSelection.Port -> {
        val data = nodes.firstOrNull { it.id == selection.nodeId }?.data as? ReaktorGraphNodeData
        val ports = if (selection.direction == ReaktorPortDirection.Provider) data?.providerPorts else data?.consumerPorts
        ports.orEmpty().any { it.handleId == selection.handleId }
    }
}

/** Legacy owner callbacks may update the host's subject too; publish the exact reference last. */
fun ReaktorFlowGraph.selectGraphSubject(
    subject: ReaktorGraphSelection?,
    onSelectNode: (Node?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onSelectSubject: (ReaktorGraphSelection?) -> Unit,
) {
    val ownerId = when (subject) {
        is ReaktorGraphSelection.Element -> subject.id
        is ReaktorGraphSelection.Port -> subject.nodeId
        else -> null
    }
    when {
        subject is ReaktorGraphSelection.Connection -> Unit
        ownerId != null && graphNodes[ownerId] != null -> onSelectNode(graphNodes[ownerId])
        else -> {
            onSelectNode(null)
            onSelectGraph(ownerId?.takeIf(graphs::containsKey))
        }
    }
    onSelectSubject(subject)
}

data class ReaktorGraphPortInspection(
    val owner: Node?,
    val port: ReaktorPortData,
    val runtimePorts: List<Port<*>>,
    val connections: List<ReaktorGraphSelection.Connection>,
)

fun ReaktorFlowGraph.inspectPort(selection: ReaktorGraphSelection.Port): ReaktorGraphPortInspection? {
    val data = nodes.firstOrNull { it.id == selection.nodeId }?.data as? ReaktorGraphNodeData ?: return null
    val provider = selection.direction == ReaktorPortDirection.Provider
    val port = (if (provider) data.providerPorts else data.consumerPorts)
        .firstOrNull { it.handleId == selection.handleId } ?: return null
    val owner = graphNodes[selection.nodeId]
    val runtimePorts = owner?.let { node ->
        val candidates = if (provider) node.providerPorts.flattenedValues() else node.consumerPorts.flattenedValues()
        candidates.filter { candidate ->
            candidate.key.key.ifBlank { candidate.type.type } == selection.handleId && shortType(candidate.type.type) == port.type
        }.toList()
    }.orEmpty()
    val connections = edges.filter { edge ->
        if (provider) edge.source == selection.nodeId && edge.sourceHandle == selection.handleId
        else edge.target == selection.nodeId && edge.targetHandle == selection.handleId
    }.map { ReaktorGraphSelection.Connection(it.id) }
    return ReaktorGraphPortInspection(owner, port, runtimePorts, connections)
}
