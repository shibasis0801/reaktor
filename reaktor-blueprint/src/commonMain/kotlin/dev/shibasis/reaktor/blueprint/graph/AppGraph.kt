package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.core.utils.epochTime
import dev.shibasis.reaktor.graph.core.DormantLifecycle
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape

const val IslandScopePrefix = "island:"
const val CallPrefix = "call:"

enum class Role(val label: String, val lane: Int) {
    Service("Services", 0),
    Data("Data", 1),
    Logic("Logic", 2),
    Screen("Screens", 3),
    Container("Containers", 4),
}

sealed interface GraphSource {
    val id: String
    val label: String

    data object Desktop : GraphSource {
        override val id = "desktop"
        override val label = "This desktop"
    }

    data object System : GraphSource {
        override val id = "system"
        override val label = "Whole system"
    }

    data class Device(override val id: String, override val label: String) : GraphSource
}

data class Wire(
    val id: String,
    val provider: String,
    val providerPort: String,
    val consumer: String,
    val consumerPort: String,
    val type: String,
)

data class Hop(val from: String, val to: String) {
    val id: String get() = "$from>$to"
}

class AppGraph(
    val shape: GraphShape,
    val source: GraphSource = GraphSource.Desktop,
    val takenAtMillis: Long = epochTime(),
) {
    val scopes: Map<String, ScopeShape> = shape.scopes.associateBy { it.id }
    val root: ScopeShape = shape.scopes.first { it.parent == null }
    private val allNodes: Map<String, NodeShape> = shape.nodes.associateBy { it.id }
    private val attachedTo: Map<String, String> = shape.nodes
        .filter { it.attachedTo != null && it.kind != NodeKind.Route }
        .associate { it.attachedTo!! to it.id }

    val nodes: Map<String, NodeShape> = shape.nodes.filter { it.kind != NodeKind.Route }.associateBy { it.id }
    val routes: Map<String, NodeShape> = shape.nodes.filter { it.kind == NodeKind.Route }.associateBy { it.id }

    val wires: List<Wire> = shape.wires.mapNotNull { wire ->
        if (wire.consumerPort in InternalPorts || wire.providerPort in InternalPorts) return@mapNotNull null
        val provider = visibleFor(wire.provider) ?: return@mapNotNull null
        val consumer = visibleFor(wire.consumer) ?: return@mapNotNull null
        Wire("${provider}.${wire.providerPort}>${consumer}.${wire.consumerPort}", provider, wire.providerPort, consumer, wire.consumerPort, wire.type)
    }.distinctBy { it.id }

    val hops: List<Hop> = shape.routes.mapNotNull { route ->
        val from = attachedTo[route.from] ?: return@mapNotNull null
        val to = attachedTo[route.to] ?: return@mapNotNull null
        Hop(from, to).takeIf { from != to }
    }.distinctBy { it.id }

    val containers: Map<String, List<ScopeShape>> = shape.scopes.filter { it.container != null }.groupBy { it.container!! }
    val children: Map<String, List<ScopeShape>> = shape.scopes.filter { it.parent != null }.groupBy { it.parent!! }
    val members: Map<String, List<NodeShape>> = nodes.values.sortedWith(compareBy({ it.label }, { it.type }, { it.id })).groupBy { it.scope }

    val wiresById: Map<String, Wire> = wires.associateBy { it.id }
    private val outgoing = wires.groupBy { it.provider }
    private val incoming = wires.groupBy { it.consumer }
    private val hopsFrom = hops.groupBy { it.from }
    private val hopsTo = hops.groupBy { it.to }

    operator fun get(id: String): NodeShape? = nodes[id]

    fun role(node: NodeShape): Role = when (node.kind) {
        NodeKind.Service -> Role.Service
        NodeKind.Interactor, NodeKind.Controller, NodeKind.Actor -> Role.Logic
        NodeKind.Screen -> Role.Screen
        NodeKind.Container -> Role.Container
        NodeKind.Route -> Role.Data
        NodeKind.Node -> if (LogicSuffixes.any { node.label.endsWith(it) || node.type.endsWith(it) }) Role.Logic else Role.Data
    }

    fun wiresOut(id: String): List<Wire> = outgoing[id].orEmpty()
    fun wiresIn(id: String): List<Wire> = incoming[id].orEmpty()
    fun hopsFrom(id: String): List<Hop> = hopsFrom[id].orEmpty()
    fun hopsTo(id: String): List<Hop> = hopsTo[id].orEmpty()

    fun ports(node: NodeShape): List<PortShape> = node.ports.filterNot { it.internal }

    fun endpoint(port: PortShape): Boolean = port.type.startsWith("op:") || port.type.startsWith(CallPrefix)

    fun island(scope: String): String? = scopePath(scope).firstOrNull { it.id.startsWith(IslandScopePrefix) }?.id

    fun scopePath(scope: String): List<ScopeShape> =
        generateSequence(scopes[scope]) { current -> current.parent?.let(scopes::get) }.toList().reversed()

    fun scopeLabel(scope: String): String = scopePath(scope).drop(1).joinToString(" › ") { it.label }.ifBlank { "root" }

    fun descendants(scope: String): Set<String> =
        generateSequence(listOf(scope)) { frontier -> frontier.flatMap { children[it].orEmpty().map(ScopeShape::id) }.takeIf { it.isNotEmpty() } }
            .flatten().toSet()

    fun containerOf(scope: String): NodeShape? = scopes[scope]?.container?.let(nodes::get)

    fun activeScreens(): Set<String> = shape.scopes.filter { scope -> scope.active && scopePath(scope.id).all { it.active } }
        .mapNotNull { scope -> scope.backStack.lastOrNull()?.let { attachedTo[it] ?: it } }
        .toSet()

    fun backStack(scope: String): List<String> = scopes[scope]?.backStack.orEmpty().mapNotNull { attachedTo[it] ?: it.takeIf(nodes::containsKey) }

    fun routeOf(node: NodeShape): String? = node.route

    val counts: Map<Role, Int> = nodes.values.groupingBy(::role).eachCount()

    private fun visibleFor(id: String): String? {
        val node = allNodes[id] ?: return null
        return if (node.kind == NodeKind.Route) attachedTo[id] else id
    }

    companion object {
        val InternalPorts = setOf("routeBinding", "navBinding")
        private val LogicSuffixes = listOf("Interactor", "Controller", "Presenter", "ViewModel")
    }
}

fun displayKey(key: String): String {
    if (key.startsWith("/") || '.' !in key) return key
    val parts = key.split('.')
    return parts.takeLast(2).joinToString(".")
}

fun PortShape.problem(): Boolean = !provides && wiring == PortWiring.Unmatched

fun lifecycleLabel(state: String): String = when (state) {
    "Attaching" -> "Attached"
    "Saving" -> "Saved"
    "Destroying" -> "Destroyed"
    DormantLifecycle -> "Not running"
    else -> state
}
