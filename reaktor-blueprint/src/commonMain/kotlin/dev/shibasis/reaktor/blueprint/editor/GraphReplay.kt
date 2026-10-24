package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.RouteShape

data class Replay(val shape: GraphShape, val conflicts: List<GraphChange>)

fun GraphShape.replay(part: GraphPart): Replay {
    val moved = moved(part.definitions)
    val changes = part.changes.map { it.remapped(moved) }
    val twins = twins(changes)
    val conflicts = ArrayList<GraphChange>()
    val shape = changes.indices.fold(this) { shape, i -> shape.replaying(changes[i].remapped(twins)) ?: shape.also { conflicts += part.changes[i] } }
    return Replay(shape, conflicts)
}

fun GraphShape.applied(part: GraphPart): Boolean =
    part.changes.isNotEmpty() && replay(part).let { it.conflicts.isEmpty() && GraphDiff(this, it.shape).empty }

private fun GraphShape.moved(definitions: Map<String, String>): Map<String, String> {
    val present = nodes.mapTo(HashSet()) { it.id } + scopes.map { it.id }
    val nodesByDefinition = nodes.filter { it.definitionId.isNotEmpty() }.groupBy { it.definitionId }
    val scopesByDefinition = scopes.filter { it.definitionId.isNotEmpty() }.groupBy { it.definitionId }
    return definitions.filterKeys { it !in present }.mapNotNull { (id, definition) ->
        (nodesByDefinition[definition]?.singleOrNull()?.id ?: scopesByDefinition[definition]?.singleOrNull()?.id)?.let { id to it }
    }.toMap()
}

private fun GraphShape.twins(changes: List<GraphChange>): Map<String, String> {
    val taken = HashSet<String>()
    return changes.mapNotNull { change ->
        val added = change.node?.takeIf { change.op == "addNode" && node(it.id) == null } ?: return@mapNotNull null
        nodes.firstOrNull { it.id !in taken && it.label == added.label && it.type == added.type && it.kind == added.kind && it.scope == added.scope }
            ?.let { taken += it.id; added.id to it.id }
    }.toMap()
}

private fun GraphChange.remapped(ids: Map<String, String>): GraphChange {
    if (ids.isEmpty()) return this
    fun id(value: String) = ids[value] ?: value
    fun NodeShape.moved() = copy(id = id(id), scope = id(scope), attachedTo = attachedTo?.let(::id), ports = ports.map { it.copy(key = id(it.key)) })
    return copy(
        node = node?.moved(),
        before = before?.moved(),
        wire = wire?.let { it.copy(consumer = id(it.consumer), consumerPort = id(it.consumerPort), provider = id(it.provider), providerPort = id(it.providerPort)) },
        route = route?.let { RouteShape(id(it.from), id(it.to)) },
        graph = graph?.let { it.copy(id = id(it.id), parent = it.parent?.let(::id), container = it.container?.let(::id), backStack = it.backStack.map(::id)) },
    )
}

private fun GraphShape.replaying(change: GraphChange): GraphShape? {
    fun has(id: String) = node(id) != null
    fun scoped(id: String?) = id == null || scopes.any { it.id == id }
    fun port(id: String, key: String, provides: Boolean) = node(id)?.port(key, provides) != null
    val next by lazy { applying(listOf(change)) }
    return when (change.op) {
        "addGraph" -> change.graph?.let { if (scopes.any { scope -> scope.id == it.id }) this else if (scoped(it.parent)) next else null }
        "changeGraph" -> change.graph?.let { if (scopes.any { scope -> scope.id == it.id } && scoped(it.parent)) next else null }
        "addNode" -> change.node?.let { if (has(it.id)) this else if (scoped(it.scope)) next else null }
        "changeNode" -> {
            val after = change.node
            val before = change.before
            val live = after?.let { node(it.id) }
            if (after == null || before == null || live == null) null else live.merged(before, after).takeIf { scoped(it.scope) }?.let { this.with(it) }
        }
        "connect" -> change.wire?.let { wire ->
            if (wires.any { it.id == wire.id }) this
            else if (port(wire.provider, wire.providerPort, true) && port(wire.consumer, wire.consumerPort, false)) next else null
        }
        "addRoute" -> change.route?.let { if (has(it.from) && has(it.to)) next else null }
        else -> next
    }
}

private fun NodeShape.merged(before: NodeShape, after: NodeShape): NodeShape {
    fun <T> pick(mine: T, old: T, new: T): T = if (new != old) new else mine
    val was = before.ports.mapTo(hashSetOf()) { it.identity }
    val now = after.ports.mapTo(hashSetOf()) { it.identity }
    val merged = ports.filterTo(ArrayList()) { it.identity !in was || it.identity in now }
    after.ports.forEachIndexed { i, port ->
        if (port.identity in was || merged.any { it.identity == port.identity }) return@forEachIndexed
        val anchor = after.ports.subList(0, i).lastOrNull { prior -> merged.any { it.identity == prior.identity } }
        merged.add(anchor?.let { prior -> merged.indexOfFirst { it.identity == prior.identity } + 1 } ?: 0, port)
    }
    return copy(
        label = pick(label, before.label, after.label),
        type = pick(type, before.type, after.type),
        kind = pick(kind, before.kind, after.kind),
        scope = pick(scope, before.scope, after.scope),
        route = pick(route, before.route, after.route),
        attachedTo = pick(attachedTo, before.attachedTo, after.attachedTo),
        ports = merged,
        operations = pick(operations, before.operations, after.operations),
        access = pick(access, before.access, after.access),
        attributes = attributes.filterKeys { it !in before.attributes || it in after.attributes } + after.attributes.filter { (key, value) -> before.attributes[key] != value },
    )
}

private val PortShape.identity get() = Triple(key, type, provides)
