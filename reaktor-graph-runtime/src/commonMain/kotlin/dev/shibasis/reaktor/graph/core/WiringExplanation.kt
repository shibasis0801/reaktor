package dev.shibasis.reaktor.graph.core

import kotlinx.serialization.Serializable

@Serializable
enum class WiringReason { SameKey, OnlyOfType, BlankKey, ParentGraph, OtherGraph, Explicit, Injected, Unmatched, Ambiguous, Consumed, Unused }

@Serializable
enum class NearMiss { KeyDiffers, TypeDiffers, OutOfReach, Ambiguous, Skipped }

@Serializable
data class PortPeer(val node: String, val label: String, val port: String, val scope: String)

@Serializable
data class PortCandidate(val node: String, val label: String, val port: String, val type: String, val scope: String, val miss: NearMiss)

@Serializable
data class PortExplanation(
    val node: String,
    val port: String,
    val type: String,
    val provides: Boolean,
    val reason: WiringReason,
    val summary: String,
    val peers: List<PortPeer> = emptyList(),
    val candidates: List<PortCandidate> = emptyList(),
) {
    val qualifier: String get() = "Port:$port:$type"
}

fun GraphShape.explain(nodeId: String): List<PortExplanation> {
    val node = nodes.firstOrNull { it.id == nodeId } ?: return emptyList()
    return node.ports.map { WiringExplainer(this).explain(node, it) }
}

fun GraphShape.explain(nodeId: String, port: String, provides: Boolean): PortExplanation? {
    val node = nodes.firstOrNull { it.id == nodeId } ?: return null
    val shape = node.ports.firstOrNull { it.key == port && it.provides == provides } ?: return null
    return WiringExplainer(this).explain(node, shape)
}

private class WiringExplainer(private val shape: GraphShape) {
    private val scopes = shape.scopes.associateBy { it.id }
    private val byId = shape.nodes.associateBy { it.id }
    private val providers = shape.nodes.flatMap { node -> node.ports.filter { it.provides }.map { node to it } }
    private val consumers = shape.nodes.flatMap { node -> node.ports.filter { !it.provides }.map { node to it } }

    fun explain(node: NodeShape, port: PortShape): PortExplanation =
        if (port.provides) provided(node, port) else consumed(node, port)

    private fun consumed(node: NodeShape, port: PortShape): PortExplanation {
        val reach = reach(node.scope)
        val sameType = providers.filter { (owner, provided) -> owner.id != node.id && provided.type == port.type }
        val local = sameType.filter { it.first.scope == node.scope }
        val wire = shape.wires.firstOrNull { it.consumer == node.id && it.consumerPort == port.key }
        val provider = wire?.let { byId[it.provider] }
        if (wire != null && provider != null) {
            val peer = PortPeer(provider.id, provider.label, wire.providerPort, provider.scope)
            val at = "${provider.label}.${display(wire.providerPort)}"
            val reason = when {
                provider.scope != node.scope && provider.scope in reach -> WiringReason.ParentGraph
                provider.scope != node.scope -> WiringReason.OtherGraph
                wire.providerPort == port.key -> WiringReason.SameKey
                port.key.isEmpty() && local.size == 1 -> WiringReason.OnlyOfType
                port.key.isEmpty() && wire.providerPort.isEmpty() -> WiringReason.BlankKey
                else -> WiringReason.Explicit
            }
            val summary = when (reason) {
                WiringReason.SameKey -> "Connected to $at: same type, same key ${quoted(port.key)}."
                WiringReason.OnlyOfType -> "Connected to $at: the key is blank and it is the only ${simple(port.type)} provider in this graph."
                WiringReason.BlankKey -> "Connected to $at: the key is blank, and of the ${local.size} ${simple(port.type)} providers in this graph it is the one with a blank key."
                WiringReason.ParentGraph -> "Connected to $at in ${scopeLabel(provider.scope)}, a parent of this graph; nothing in this graph matched first."
                WiringReason.OtherGraph -> "Connected to $at in ${scopeLabel(provider.scope)}, outside this graph and its parents, so an explicit connect or an imported graph made this wire."
                else -> "Connected to $at by an explicit connect; matching by key and type alone would not choose it."
            }
            return PortExplanation(node.id, port.key, port.type, false, reason, summary, listOf(peer))
        }
        if (port.wiring == PortWiring.Injected) return PortExplanation(node.id, port.key, port.type, false, WiringReason.Injected,
            "No wire. Dependency injection holds a provider under ${quoted("Port:${port.key}:${port.type}")}, which is how this port is satisfied.")
        val ambiguous = port.key.isEmpty() && local.size > 1 && local.none { it.second.key.isEmpty() }
        val candidates = buildList {
            sameType.forEach { (owner, provided) ->
                val miss = when {
                    owner.scope !in reach -> NearMiss.OutOfReach
                    ambiguous && owner.scope == node.scope -> NearMiss.Ambiguous
                    port.key.isNotEmpty() && provided.key != port.key -> NearMiss.KeyDiffers
                    else -> null
                }
                miss?.let { add(candidate(owner, provided, it)) }
            }
            if (port.key.isNotEmpty()) providers.filter { (owner, provided) ->
                owner.id != node.id && owner.scope in reach && provided.key == port.key && provided.type != port.type
            }.forEach { (owner, provided) -> add(candidate(owner, provided, NearMiss.TypeDiffers)) }
            providers.filter { (owner, provided) ->
                owner.id != node.id && owner.scope in reach && provided.type != port.type && provided.key != port.key && simple(provided.type) == simple(port.type)
            }.forEach { (owner, provided) -> add(candidate(owner, provided, NearMiss.TypeDiffers)) }
        }.distinct().sortedWith(compareBy({ it.miss.ordinal }, { it.label }, { it.port }))
        val looked = if (port.key.isEmpty()) "the only ${simple(port.type)} provider in this graph, or the one with a blank key"
            else "a ${simple(port.type)} provider keyed ${quoted(port.key)} in this graph"
        val summary = if (ambiguous) "${local.size} providers of ${simple(port.type)} in this graph and none has a blank key, so a blank key cannot choose. Name one: ${local.joinToString { quoted(it.second.key) }}."
            else "Nothing provides it. Wiring looks for $looked, then in its parent graphs, then in dependency injection under ${quoted("Port:${port.key}:${port.type}")}." +
                if (candidates.isEmpty()) "" else " ${candidates.size} near ${if (candidates.size == 1) "miss" else "misses"} below."
        return PortExplanation(node.id, port.key, port.type, false, if (ambiguous) WiringReason.Ambiguous else WiringReason.Unmatched, summary, candidates = candidates)
    }

    private fun provided(node: NodeShape, port: PortShape): PortExplanation {
        val peers = shape.wires.filter { it.provider == node.id && it.providerPort == port.key }
            .mapNotNull { wire -> byId[wire.consumer]?.let { PortPeer(it.id, it.label, wire.consumerPort, it.scope) } }
        if (peers.isNotEmpty()) return PortExplanation(node.id, port.key, port.type, true, WiringReason.Consumed,
            "Feeds ${peers.size} ${if (peers.size == 1) "consumer" else "consumers"}: ${peers.take(3).joinToString { "${it.label}.${display(it.port)}" }}${if (peers.size > 3) " and ${peers.size - 3} more" else ""}.", peers)
        val waiting = consumers.filter { (owner, consumed) ->
            owner.id != node.id && consumed.type == port.type && consumed.wiring == PortWiring.Unmatched
        }.map { (owner, consumed) ->
            candidate(owner, consumed, when {
                node.scope !in reach(owner.scope) -> NearMiss.OutOfReach
                consumed.key.isNotEmpty() && consumed.key != port.key -> NearMiss.KeyDiffers
                else -> NearMiss.Skipped
            })
        }.sortedWith(compareBy({ it.miss.ordinal }, { it.label }))
        return PortExplanation(node.id, port.key, port.type, true, WiringReason.Unused,
            if (waiting.isEmpty()) "Nothing consumes it." else "Nothing consumes it, yet ${waiting.size} unconnected ${if (waiting.size == 1) "port asks" else "ports ask"} for ${simple(port.type)}.",
            candidates = waiting)
    }

    private fun candidate(owner: NodeShape, port: PortShape, miss: NearMiss) =
        PortCandidate(owner.id, owner.label, port.key, port.type, owner.scope, miss)

    private fun reach(scope: String): Set<String> =
        generateSequence(scopes[scope]) { current -> current.parent?.let(scopes::get) }.map { it.id }.toSet() + scope

    private fun scopeLabel(scope: String) = scopes[scope]?.label ?: "another graph"

    private fun simple(type: String) = type.substringBefore('<').substringAfterLast('.')

    private fun display(key: String) = key.ifEmpty { "(blank)" }

    private fun quoted(value: String) = if (value.isEmpty()) "(blank)" else "`$value`"
}
