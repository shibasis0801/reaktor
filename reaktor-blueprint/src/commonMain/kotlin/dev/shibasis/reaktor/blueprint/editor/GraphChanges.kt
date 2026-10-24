package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlinx.serialization.Serializable

@Serializable
data class GraphChange(
    val op: String,
    val summary: String,
    val node: NodeShape? = null,
    val before: NodeShape? = null,
    val wire: WireShape? = null,
    val route: RouteShape? = null,
    val graph: ScopeShape? = null,
)

fun GraphShape.changesTo(next: GraphShape, terms: GraphTerms = GraphTerms): List<GraphChange> {
    val diff = GraphDiff(this, next, terms)
    val before = nodes.associateBy { it.id }
    val after = next.nodes.associateBy { it.id }
    val wiresBefore = wires.associateBy { it.id }
    val wiresAfter = next.wires.associateBy { it.id }
    val routesBefore = routes.associateBy { it.id }
    val routesAfter = next.routes.associateBy { it.id }
    val scopesBefore = scopes.associateBy { it.id }
    val scopesAfter = next.scopes.associateBy { it.id }
    fun name(id: String) = (after[id] ?: before[id])?.label ?: id
    fun end(id: String, key: String) = (after[id] ?: before[id])?.let { terms.end(it, key) } ?: "$id.$key"
    fun link(wire: WireShape) = "${end(wire.provider, wire.providerPort)} → ${end(wire.consumer, wire.consumerPort)} (${wire.type})"
    return buildList {
        scopesAfter.values.filter { it.id !in scopesBefore }.forEach { add(GraphChange("addGraph", "Add the graph ${it.label}", graph = it)) }
        scopesAfter.values.filter { scope -> scopesBefore[scope.id]?.let { it.label != scope.label || it.parent != scope.parent } == true }
            .forEach { add(GraphChange("changeGraph", "Rename or move the graph ${scopesBefore.getValue(it.id).label} to ${it.label}", graph = it)) }
        diff.nodes.filterValues { it == Change.Added }.keys.mapNotNull(after::get).forEach { node ->
            add(GraphChange("addNode", "Add ${terms.summary(node, Change.Added)} named ${node.label}", node = node))
        }
        diff.nodes.filterValues { it == Change.Changed }.keys.forEach { id ->
            val old = before.getValue(id)
            val new = after.getValue(id)
            add(GraphChange("changeNode", "Change ${old.label}: ${diff.differences.firstOrNull { it.subject == id }?.detail.orEmpty()}", node = new, before = old))
        }
        wiresAfter.values.filter { it.id !in wiresBefore }.forEach { add(GraphChange("connect", "Connect ${link(it)}", wire = it)) }
        routesAfter.values.filter { it.id !in routesBefore }.forEach { add(GraphChange("addRoute", "Add a route from ${name(it.from)} to ${name(it.to)}", route = it)) }
        wiresBefore.values.filter { it.id !in wiresAfter }.forEach { add(GraphChange("disconnect", "Disconnect ${link(it)}", wire = it)) }
        routesBefore.values.filter { it.id !in routesAfter }.forEach { add(GraphChange("removeRoute", "Remove the route from ${name(it.from)} to ${name(it.to)}", route = it)) }
        diff.nodes.filterValues { it == Change.Removed }.keys.mapNotNull(before::get).forEach { node ->
            add(GraphChange("removeNode", "Remove ${terms.summary(node, Change.Removed)} named ${node.label}", before = node))
        }
        scopesBefore.values.filter { it.id !in scopesAfter }.forEach { add(GraphChange("removeGraph", "Remove the graph ${it.label}", graph = it)) }
    }
}

fun GraphShape.applying(changes: List<GraphChange>): GraphShape = changes.fold(this) { shape, change ->
    when (change.op) {
        "addGraph", "changeGraph" -> change.graph?.let { scope -> shape.copy(scopes = shape.scopes.filterNot { it.id == scope.id } + scope) } ?: shape
        "removeGraph" -> change.graph?.let { scope -> shape.copy(scopes = shape.scopes.filterNot { it.id == scope.id }) } ?: shape
        "addNode" -> change.node?.let(shape::with) ?: shape
        "changeNode" -> change.node?.takeIf { shape.node(it.id) != null }?.let(shape::with) ?: shape
        "removeNode" -> change.before?.let { shape.without(setOf(it.id)) } ?: shape
        "connect" -> change.wire?.takeIf { shape.node(it.provider) != null && shape.node(it.consumer) != null }
            ?.let { wire -> shape.copy(wires = shape.wires.filterNot { it.id == wire.id } + wire).recounted() } ?: shape
        "disconnect" -> change.wire?.let { shape.disconnect(setOf(it.id)) } ?: shape
        "addRoute" -> change.route?.takeIf { shape.node(it.from) != null && shape.node(it.to) != null }
            ?.let { route -> shape.copy(routes = shape.routes.filterNot { it.id == route.id } + route) } ?: shape
        "removeRoute" -> change.route?.let { shape.disconnect(setOf(it.id)) } ?: shape
        else -> shape
    }
}

fun GraphShape.definitionsFor(changes: List<GraphChange>): Map<String, String> {
    val referenced = changes.flatMapTo(HashSet()) { it.references() }
    return (nodes.map { it.id to it.definitionId } + scopes.map { it.id to it.definitionId })
        .filter { (id, definition) -> id in referenced && definition.isNotEmpty() }.toMap()
}

fun GraphShape.rebased(changes: List<GraphChange>, definitions: Map<String, String>): List<GraphChange> {
    val present = nodes.mapTo(HashSet()) { it.id } + scopes.map { it.id }
    val nodesByDefinition = nodes.filter { it.definitionId.isNotEmpty() }.groupBy { it.definitionId }
    val scopesByDefinition = scopes.filter { it.definitionId.isNotEmpty() }.groupBy { it.definitionId }
    val ids = definitions.filterKeys { it !in present }.mapNotNull { (id, definition) ->
        (nodesByDefinition[definition]?.singleOrNull()?.id ?: scopesByDefinition[definition]?.singleOrNull()?.id)?.let { id to it }
    }.toMap()
    if (ids.isEmpty()) return changes
    val current = nodes.associateBy { it.id }
    fun id(value: String) = ids[value] ?: value
    fun NodeShape.remapped() = copy(id = id(id), scope = id(scope), attachedTo = attachedTo?.let(::id), ports = ports.map { it.copy(key = id(it.key)) })
    return changes.map { change ->
        val node = change.node?.remapped()
        val before = change.before?.remapped()
        val live = node?.let { current[it.id] }.takeIf { change.op == "changeNode" && before != null }
        change.copy(
            node = if (live != null && before != null && node != null) live.merging(before, node) else node,
            before = live ?: before,
            wire = change.wire?.let { it.copy(consumer = id(it.consumer), consumerPort = id(it.consumerPort), provider = id(it.provider), providerPort = id(it.providerPort)) },
            route = change.route?.let { RouteShape(id(it.from), id(it.to)) },
            graph = change.graph?.let { it.copy(id = id(it.id), parent = it.parent?.let(::id), container = it.container?.let(::id), backStack = it.backStack.map(::id)) },
        )
    }
}

private fun GraphChange.references(): List<String> =
    listOfNotNull(node?.id, node?.scope, node?.attachedTo, before?.id, before?.scope, before?.attachedTo, wire?.consumer, wire?.consumerPort, wire?.provider,
        wire?.providerPort, route?.from, route?.to, graph?.id, graph?.parent, graph?.container) + (node?.ports.orEmpty() + before?.ports.orEmpty()).map { it.key }

private fun NodeShape.merging(before: NodeShape, after: NodeShape): NodeShape {
    fun <T> chosen(mine: T, old: T, new: T): T = if (new != old) new else mine
    return copy(
        label = chosen(label, before.label, after.label),
        type = chosen(type, before.type, after.type),
        kind = chosen(kind, before.kind, after.kind),
        scope = chosen(scope, before.scope, after.scope),
        route = chosen(route, before.route, after.route),
        attachedTo = chosen(attachedTo, before.attachedTo, after.attachedTo),
        ports = if (after.ports.map { Triple(it.key, it.type, it.provides) } != before.ports.map { Triple(it.key, it.type, it.provides) }) after.ports else ports,
        operations = chosen(operations, before.operations, after.operations),
        access = chosen(access, before.access, after.access),
        attributes = attributes.filterKeys { it !in before.attributes || it in after.attributes } + after.attributes.filter { (key, value) -> before.attributes[key] != value },
    )
}

fun Placement.with(moved: Placement): Placement {
    val current = notes.mapTo(HashSet()) { it.id }
    val kept = moved.notes.filter { it.id in current || it.id.startsWith(CommentPrefix) }
    val replaced = kept.mapTo(HashSet()) { it.id } + moved.removedNotes
    return Placement(cards + moved.cards, notes.filter { it.id !in replaced } + kept, bends + moved.bends, marks = marks + moved.marks, remarks = remarks + moved.remarks)
}

fun Placement.since(origin: Placement): Placement = Placement(
    cards = cards.filter { (id, at) -> origin.cards[id] != at },
    notes = notes.filter { it !in origin.notes },
    bends = bends.filter { (id, path) -> origin.bends[id] != path },
    removedNotes = origin.notes.map { it.id }.filterTo(HashSet()) { id -> notes.none { it.id == id } },
    marks = marks.filter { (key, mark) -> origin.marks[key] != mark },
    remarks = remarks.filter { (id, text) -> origin.remarks[id] != text },
)

internal const val CommentPrefix = "note-"
