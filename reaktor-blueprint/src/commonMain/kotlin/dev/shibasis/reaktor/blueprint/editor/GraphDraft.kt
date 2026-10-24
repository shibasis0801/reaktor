package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlinx.serialization.Serializable

@Serializable
data class Spot(val x: Double, val y: Double)

@Serializable
data class Note(val id: String, val text: String, val x: Double, val y: Double, val width: Double, val height: Double, val tint: Long = 0, val parent: String? = null)

@Serializable
data class Mark(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class Placement(
    val cards: Map<String, Spot> = emptyMap(),
    val notes: List<Note> = emptyList(),
    val bends: Map<String, List<Spot>> = emptyMap(),
    val removedNotes: Set<String> = emptySet(),
    val marks: Map<Int, Mark> = emptyMap(),
    val remarks: Map<String, String> = emptyMap(),
)

data class GraphDraft(
    val id: String,
    val title: String,
    val revision: Int,
    val savedAtMillis: Long,
    val base: GraphShape,
    val shape: GraphShape,
    val placement: Placement,
)

data class Verdict(val ok: Boolean, val message: String)

data class Problem(val subject: String, val text: String, val port: String? = null, val path: List<String> = emptyList())

val WireShape.id: String get() = "$provider.$providerPort>$consumer.$consumerPort"

val RouteShape.id: String get() = "$from>$to"

fun GraphShape.node(id: String): NodeShape? = nodes.firstOrNull { it.id == id }

fun NodeShape.port(key: String, provides: Boolean): PortShape? = ports.firstOrNull { it.key == key && it.provides == provides }

fun GraphShape.with(node: NodeShape): GraphShape {
    val replaced = nodes.any { it.id == node.id }
    val next = copy(nodes = if (replaced) nodes.map { if (it.id == node.id) node else it } else nodes + node)
    return next.copy(wires = wires.filter { it.provider != node.id && it.consumer != node.id || !fits(it) || next.fits(it) }).recounted()
}

fun GraphShape.without(ids: Set<String>): GraphShape = copy(
    nodes = nodes.filter { it.id !in ids },
    wires = wires.filter { it.provider !in ids && it.consumer !in ids },
    routes = routes.filter { it.from !in ids && it.to !in ids },
).recounted()

fun GraphShape.connect(wire: WireShape, single: Boolean = true): GraphShape =
    copy(wires = wires.filterNot { it.consumer == wire.consumer && it.consumerPort == wire.consumerPort && (single || it.provider == wire.provider && it.providerPort == wire.providerPort) } + wire).recounted()

fun GraphShape.disconnect(ids: Set<String>): GraphShape = copy(wires = wires.filter { it.id !in ids }, routes = routes.filter { it.id !in ids }).recounted()

val Exact: (String, String) -> Boolean = { provided, needed -> provided == needed }

fun GraphShape.verdict(provider: String, providerPort: String, consumer: String, consumerPort: String, single: Boolean = true, fits: (String, String) -> Boolean = Exact,
    via: (String, String) -> String? = { _, _ -> null }): Verdict {
    val from = node(provider)?.port(providerPort, true) ?: return Verdict(false, "Drag from an output to an input")
    val to = node(consumer)?.port(consumerPort, false) ?: return Verdict(false, "Drag from an output to an input")
    if (provider == consumer) return Verdict(false, "A node cannot feed itself")
    if (!fits(from.type, to.type)) return Verdict(false, "Type mismatch: needs ${to.type}, provides ${from.type}")
    val existing = wires.filter { it.consumer == consumer && it.consumerPort == consumerPort }
    if (existing.any { it.provider == provider && it.providerPort == providerPort }) return Verdict(false, "Already connected")
    val how = if (from.type == to.type) "" else " · " + (via(from.type, to.type) ?: "${from.type.substringAfterLast('.')} fits ${to.type.substringAfterLast('.')}")
    if (single && existing.isNotEmpty()) return Verdict(true, "Replace the link from ${node(existing.first().provider)?.label ?: existing.first().provider}$how")
    return Verdict(true, "Connect ${from.key} to ${to.key}$how")
}

fun GraphShape.wire(provider: String, providerPort: String, consumer: String, consumerPort: String, single: Boolean = true, fits: (String, String) -> Boolean = Exact): WireShape? =
    verdict(provider, providerPort, consumer, consumerPort, single, fits).takeIf { it.ok }?.let { WireShape(consumer, consumerPort, provider, providerPort, node(consumer)!!.port(consumerPort, false)!!.type) }

fun GraphShape.recounted(): GraphShape {
    val out = wires.groupingBy { it.provider to it.providerPort }.eachCount()
    val into = wires.mapTo(hashSetOf()) { it.consumer to it.consumerPort }
    return copy(nodes = nodes.map { node ->
        node.copy(ports = node.ports.map { port ->
            if (port.provides) out[node.id to port.key].let { count -> port.copy(connections = count ?: 0, wiring = if (count == null) PortWiring.Unused else PortWiring.Connected) }
            else if (node.id to port.key in into) port.copy(connections = 1, wiring = PortWiring.Connected)
            else port.copy(connections = 0, wiring = if (port.wiring == PortWiring.Injected) PortWiring.Injected else PortWiring.Unmatched)
        })
    })
}

private fun GraphShape.fits(wire: WireShape): Boolean {
    val from = node(wire.provider)?.port(wire.providerPort, true)
    val to = node(wire.consumer)?.port(wire.consumerPort, false)
    return from != null && to != null && from.type == to.type
}

fun GraphShape.problems(single: Boolean = true, fits: (String, String) -> Boolean = Exact): List<Problem> = buildList {
    val scopeIds = scopes.mapTo(hashSetOf()) { it.id }
    val byId = HashMap<String, NodeShape>()
    nodes.forEach { node -> if (byId.put(node.id, node) != null) add(Problem(node.id, "Two nodes share the id ${node.id}")) }
    scopes.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add(Problem(it, "Two graphs share the id $it")) }
    val parents = scopes.associate { it.id to it.parent }
    scopes.forEach { scope ->
        if (scope.parent != null && scope.parent !in scopeIds) add(Problem(scope.id, "${scope.label} sits in a graph that does not exist"))
        var seen = 0; var at = scope.parent
        while (at != null && seen++ <= scopes.size) at = parents[at]
        if (seen > scopes.size) add(Problem(scope.id, "${scope.label} contains itself"))
    }
    nodes.forEach { node ->
        if (node.label.isBlank()) add(Problem(node.id, "A ${node.kind.name.lowercase()} needs a name"))
        if (node.scope !in scopeIds) add(Problem(node.id, "${node.label} sits in a graph that does not exist"))
        node.ports.groupBy { it.key to it.provides }.filterValues { it.size > 1 }.keys.forEach { (key, provides) ->
            add(Problem(node.id, "${node.label} has two ${if (provides) "outputs" else "inputs"} named $key"))
        }
        node.ports.filter { it.key.isBlank() }.forEach { _ -> add(Problem(node.id, "${node.label} has a port without a name")) }
    }
    val inputs = HashSet<Pair<String, String>>()
    val seenWires = HashSet<String>()
    wires.forEach { wire ->
        val from = byId[wire.provider]
        val to = byId[wire.consumer]
        val out = from?.port(wire.providerPort, true)
        val into = to?.port(wire.consumerPort, false)
        when {
            from == null || to == null -> add(Problem(wire.id, "A wire ends at a node that does not exist"))
            out == null -> add(Problem(wire.id, "${from.label} has no output ${wire.providerPort}"))
            into == null -> add(Problem(wire.id, "${to.label} has no input ${wire.consumerPort}"))
            !fits(out.type, into.type) -> add(Problem(wire.id, "${to.label}.${into.key} needs ${into.type}, ${from.label}.${out.key} provides ${out.type}"))
        }
        if (!seenWires.add(wire.id)) add(Problem(wire.id, "The same wire appears twice"))
        else if (!inputs.add(wire.consumer to wire.consumerPort) && single) add(Problem(wire.id, "${to?.label ?: wire.consumer}.${wire.consumerPort} has more than one wire"))
    }
    routes.forEach { route -> if (route.from !in byId || route.to !in byId) add(Problem(route.id, "A route ends at a node that does not exist")) }
}

fun GraphShape.injected(base: GraphShape = this): GraphShape {
    val wired = wires.mapTo(hashSetOf()) { it.consumer to it.consumerPort }
    val given = base.wires.mapTo(hashSetOf()) { it.consumer to it.consumerPort }.let { into ->
        base.nodes.flatMapTo(hashSetOf()) { node -> node.ports.filter { !it.provides && (it.wiring == PortWiring.Connected || it.wiring == PortWiring.Injected) && (node.id to it.key) !in into }.map { node.id to it.key } }
    }
    fun implicit(node: NodeShape, port: PortShape) = !port.provides && port.wiring != PortWiring.Injected &&
        (node.id to port.key).let { it !in wired && (port.wiring == PortWiring.Connected || it in given) }
    return copy(nodes = nodes.map { node -> if (node.ports.none { implicit(node, it) }) node else node.copy(ports = node.ports.map { if (implicit(node, it)) it.copy(wiring = PortWiring.Injected) else it }) })
}

fun GraphShape.unwired(): List<Problem> = nodes.filter { it.kind != NodeKind.Route }.flatMap { node ->
    node.ports.filter { !it.provides && !it.internal && it.wiring == PortWiring.Unmatched }.map { Problem(node.id, "Nothing provides ${it.type} to ${node.label}.${it.key}", it.key) }
}

fun GraphShape.introducedProblems(base: GraphShape, single: Boolean = true, fits: (String, String) -> Boolean = Exact): List<Problem> {
    val known = base.named(this).problems(single, fits).mapTo(hashSetOf()) { it.text }
    return problems(single, fits).filter { it.text !in known }
}

fun GraphShape.named(like: GraphShape): GraphShape {
    val labels = like.nodes.associate { it.id to it.label }
    return copy(nodes = nodes.map { node -> labels[node.id]?.takeIf(String::isNotBlank)?.let { node.copy(label = it) } ?: node })
}

fun GraphShape.visible(id: String): String? {
    val node = node(id) ?: return null
    return if (node.kind == NodeKind.Route) nodes.firstOrNull { it.attachedTo == id && it.kind != NodeKind.Route }?.id else id
}
