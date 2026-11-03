package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.portgraph.definition.GraphDefinition
import dev.shibasis.reaktor.portgraph.definition.PortPolarity
import kotlinx.serialization.json.Json

fun Graph.blueprintShape(): GraphShape = shape()

fun GraphDefinition.blueprintShape(): GraphShape {
    val problems = problems()
    require(problems.isEmpty()) { problems.joinToString("; ") }
    val ports = nodes.flatMap { node -> node.ports.map { (node.id to it.id) to it } }.toMap()
    val wires = relations.map { relation ->
        require(relation.incidences.size == 2) { "Blueprint requires a binary relation: ${relation.id}" }
        val ends = relation.incidences
        val offered = ends.singleOrNull { ports.getValue(it.node to it.port).polarity == PortPolarity.Offer }
        val required = ends.singleOrNull { ports.getValue(it.node to it.port).polarity == PortPolarity.Require }
        require(offered != null && required != null) { "Blueprint requires declared Offer and Require endpoints: ${relation.id}" }
        val supply = ports.getValue(offered.node to offered.port)
        val demand = ports.getValue(required.node to required.port)
        require(supply.contract == demand.contract) { "Blueprint endpoint contracts differ: ${relation.id}" }
        WireShape(required.node, required.port, offered.node, offered.port, demand.contract, sourceId = relation.id)
    }
    require(nodes.none { node -> node.ports.any { it.polarity == PortPolarity.Neutral } }) {
        "Blueprint requires declared port polarity; Neutral ports cannot be represented as providers or consumers"
    }
    val connected = wires.flatMap { listOf(it.provider to it.providerPort, it.consumer to it.consumerPort) }.groupingBy { it }.eachCount()
    val containers = regions.mapNotNull { it.container }.toSet()
    return GraphShape(
        scopes = regions.map { ScopeShape(it.id, it.label, it.parent, it.container) },
        nodes = nodes.map { node ->
            NodeShape(node.id, node.region, node.label, node.kind, if (node.id in containers) NodeKind.Container else NodeKind.Node,
                ports = node.ports.map { port ->
                    val count = connected[node.id to port.id] ?: 0
                    PortShape(port.id, port.contract, port.polarity == PortPolarity.Offer,
                        if (count > 0) PortWiring.Connected else if (port.polarity == PortPolarity.Offer) PortWiring.Unused else PortWiring.Unmatched, count)
                },
                attributes = buildMap {
                    put("portgraph.definition", id)
                    put("portgraph.revision", revision)
                    put("portgraph.evidence", "Declared")
                    node.ports.forEach { put("portgraph.profile:${it.id}", it.profile) }
                    relations.filter { relation -> relation.incidences.any { it.node == node.id } }
                        .forEach { put("portgraph.relation:${it.id}", Json.encodeToString(it)) }
                })
        },
        wires = wires,
        routes = emptyList(),
    )
}
