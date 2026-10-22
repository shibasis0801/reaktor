package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.WireShape

enum class Change(val sign: String) { Added("+"), Removed("−"), Changed("~") }

data class Difference(val change: Change, val subject: String, val node: String?, val title: String, val detail: String)

class GraphDiff(val base: GraphShape, val shape: GraphShape, val terms: GraphTerms = GraphTerms) {
    private val before = base.nodes.associateBy { it.id }
    private val after = shape.nodes.associateBy { it.id }
    private val wiresBefore = base.wires.associateBy { it.id }
    private val wiresAfter = shape.wires.associateBy { it.id }
    private val routesBefore = base.routes.associateBy { it.id }
    private val routesAfter = shape.routes.associateBy { it.id }

    val nodes: Map<String, Change> = buildMap {
        after.forEach { (id, node) -> val old = before[id]; if (old == null) put(id, Change.Added) else if (old.edited(node)) put(id, Change.Changed) }
        before.keys.filter { it !in after }.forEach { put(it, Change.Removed) }
    }

    val wires: Map<String, Change> = buildMap {
        wiresAfter.keys.filter { it !in wiresBefore }.forEach { put(it, Change.Added) }
        wiresBefore.keys.filter { it !in wiresAfter }.forEach { put(it, Change.Removed) }
        routesAfter.keys.filter { it !in routesBefore }.forEach { put(it, Change.Added) }
        routesBefore.keys.filter { it !in routesAfter }.forEach { put(it, Change.Removed) }
    }

    val removedNodes: List<NodeShape> get() = base.nodes.filter { nodes[it.id] == Change.Removed }

    val removedWires: List<WireShape> get() = base.wires.filter { wires[it.id] == Change.Removed }

    val empty: Boolean get() = nodes.isEmpty() && wires.isEmpty() && base.scopes == shape.scopes

    val differences: List<Difference> by lazy {
        buildList {
            nodes.forEach { (id, change) ->
                val node = after[id] ?: before.getValue(id)
                val detail = if (change == Change.Changed) before.getValue(id).describe(node, terms) else terms.summary(node, change)
                add(Difference(change, id, id, node.label, detail))
            }
            wires.forEach { (id, change) ->
                val wire = wiresAfter[id] ?: wiresBefore[id]
                val label = wire?.let { "${end(it.provider, it.providerPort)} → ${end(it.consumer, it.consumerPort)}" }
                    ?: (routesAfter[id] ?: routesBefore[id])?.let { "${name(it.from)} ⇢ ${name(it.to)}" } ?: id
                add(Difference(change, id, wire?.consumer, label, wire?.type ?: "route"))
            }
            val scopesBefore = base.scopes.associateBy { it.id }
            val scopesAfter = shape.scopes.associateBy { it.id }
            scopesAfter.values.filter { it.id !in scopesBefore }.forEach { add(Difference(Change.Added, it.id, null, it.label, "graph")) }
            scopesBefore.values.filter { it.id !in scopesAfter }.forEach { add(Difference(Change.Removed, it.id, null, it.label, "graph")) }
            scopesAfter.values.filter { scope -> scopesBefore[scope.id]?.let { it.label != scope.label || it.parent != scope.parent } == true }
                .forEach { add(Difference(Change.Changed, it.id, null, it.label, "graph renamed or moved")) }
        }.sortedWith(compareBy({ it.change.ordinal }, { it.title }))
    }

    private fun name(id: String) = (after[id] ?: before[id])?.label ?: id

    private fun end(id: String, key: String) = (after[id] ?: before[id])?.let { terms.end(it, key) } ?: "$id.$key"
}

private fun NodeShape.edited(other: NodeShape) = label != other.label || type != other.type || kind != other.kind || scope != other.scope ||
    route != other.route || ports.map(PortShape::signature) != other.ports.map(PortShape::signature) || attributes != other.attributes

private fun PortShape.signature() = Triple(key, type, provides)

private fun NodeShape.describe(next: NodeShape, terms: GraphTerms): String = buildList {
    if (label != next.label) add("renamed from $label")
    if (terms.types && type != next.type) add("type $type → ${next.type}")
    if (terms.kinds && kind != next.kind) add("kind ${kind.name} → ${next.kind.name}")
    if (scope != next.scope) add("moved to another graph")
    if (route != next.route) add("route ${route ?: "none"} → ${next.route ?: "none"}")
    val rows = terms.rows(this@describe); val rowsNext = terms.rows(next)
    if (rows != null && rowsNext != null) {
        (rowsNext - rows.toSet()).forEach { add("+ row ${terms.port(next, it)}") }
        (rows - rowsNext.toSet()).forEach { add("− row ${terms.port(this@describe, it)}") }
    } else {
        val old = ports.map(PortShape::signature).toSet()
        val now = next.ports.map(PortShape::signature).toSet()
        (now - old).forEach { add("+ ${if (it.third) "output" else "input"} ${it.first}: ${it.second}") }
        (old - now).forEach { add("− ${if (it.third) "output" else "input"} ${it.first}") }
    }
    if (attributes != next.attributes) add("attributes edited")
}.joinToString(" · ")
