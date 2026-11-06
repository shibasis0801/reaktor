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

    data object Build : GraphSource {
        override val id = "build"
        override val label = "Build declarations"
    }

    data object Code : GraphSource {
        override val id = "code"
        override val label = "Kotlin sources"
    }

    data class Revision(val digest: String, override val label: String) : GraphSource {
        override val id = "revision:$digest"
    }

    data class Device(override val id: String, override val label: String) : GraphSource

    data class Declared(override val id: String, override val label: String) : GraphSource
}

data class Wire(
    val id: String,
    val provider: String,
    val providerPort: String,
    val consumer: String,
    val consumerPort: String,
    val type: String,
    val condition: String? = null,
)

data class Hop(val from: String, val to: String, val exit: String = "") {
    val id: String get() = "${linkPart(from)}>${linkPart(to)}" + exit.takeIf { it.isNotEmpty() }?.let { ":${linkPart(it)}" }.orEmpty()
}

private fun linkPart(value: String): String = buildString {
    value.forEach { character ->
        if (character in "%.><|#:") append('%').append(character.code.toString(16).uppercase().padStart(2, '0'))
        else append(character)
    }
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

    private val telemetryBindings = source == GraphSource.System || source is GraphSource.Revision
    val nodes: Map<String, NodeShape> = shape.nodes.filter { telemetryBindings || it.kind != NodeKind.Route }.associateBy { it.id }
    val routes: Map<String, NodeShape> = shape.nodes.filter { it.kind == NodeKind.Route }.associateBy { it.id }

    private val boundWires = shape.wires.mapNotNull { wire ->
        if (!telemetryBindings && (internalBinding(wire.consumer, wire.consumerPort, false) || internalBinding(wire.provider, wire.providerPort, true))) return@mapNotNull null
        val provider = visibleFor(wire.provider) ?: return@mapNotNull null
        val consumer = visibleFor(wire.consumer) ?: return@mapNotNull null
        wire to Wire(wire.sourceId.ifEmpty { "${linkPart(wire.provider)}.${linkPart(wire.providerPort)}>${linkPart(wire.consumer)}.${linkPart(wire.consumerPort)}" },
            provider, wire.providerPort, consumer, wire.consumerPort, wire.type, wire.condition)
    }.distinctBy { it.second.id }
    val wires: List<Wire> = boundWires.map { it.second }
    private val projectedPorts = boundWires.flatMap { (original, shown) ->
        listOfNotNull(
            routes[original.provider]?.takeIf { it.id != shown.provider }?.ports?.singleOrNull {
                it.key == original.providerPort && it.type == original.type && it.provides
            }?.let { shown.provider to it },
            routes[original.consumer]?.takeIf { it.id != shown.consumer }?.ports?.singleOrNull {
                it.key == original.consumerPort && it.type == original.type && !it.provides
            }?.let { shown.consumer to it },
        )
    }.groupBy({ it.first }, { it.second })

    val hops: List<Hop> = shape.routes.mapNotNull { route ->
        val from = attachedTo[route.from] ?: return@mapNotNull null
        val to = attachedTo[route.to] ?: return@mapNotNull null
        Hop(from, to, route.exit)
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
        NodeKind.Interactor, NodeKind.Controller, NodeKind.Actor, NodeKind.Workflow, NodeKind.Schedule -> Role.Logic
        NodeKind.Screen -> Role.Screen
        NodeKind.Container -> Role.Container
        NodeKind.Route, NodeKind.Store -> Role.Data
        NodeKind.External -> Role.Service
        NodeKind.Node -> if (LogicSuffixes.any { node.label.endsWith(it) || node.type.endsWith(it) }) Role.Logic else Role.Data
    }

    fun wiresOut(id: String): List<Wire> = outgoing[id].orEmpty()
    fun wiresIn(id: String): List<Wire> = incoming[id].orEmpty()
    fun hopsFrom(id: String): List<Hop> = hopsFrom[id].orEmpty()
    fun hopsTo(id: String): List<Hop> = hopsTo[id].orEmpty()

    fun connection(from: String, to: String): Set<String> {
        if (from !in nodes || to !in nodes) return emptySet()
        val edges = wires.flatMap { listOf(it.provider to it.consumer, it.consumer to it.provider) } +
            hops.map { it.from to it.to }
        fun distances(start: String, adjacency: Map<String, List<String>>): Map<String, Int> {
            val seen = linkedMapOf(start to 0)
            val frontier = ArrayDeque(listOf(start))
            while (frontier.isNotEmpty()) {
                val node = frontier.removeFirst()
                adjacency[node].orEmpty().forEach { next ->
                    if (next !in seen) { seen[next] = seen.getValue(node) + 1; frontier.addLast(next) }
                }
            }
            return seen
        }
        val forward = distances(from, edges.groupBy({ it.first }, { it.second }))
        val length = forward[to] ?: return emptySet()
        val backward = distances(to, edges.groupBy({ it.second }, { it.first }))
        return forward.keys.filterTo(linkedSetOf()) { node ->
            backward[node]?.let { forward.getValue(node) + it == length } == true
        }
    }

    fun ports(node: NodeShape): List<PortShape> {
        val own = node.ports.filter { telemetryBindings || !it.internal }
        val projected = projectedPorts[node.id].orEmpty().filter { telemetryBindings || !it.internal }
            .distinctBy { Triple(it.key, it.type, it.provides) }
        return own + projected.filterNot { port -> own.any { it.key == port.key && it.type == port.type && it.provides == port.provides } }
    }

    fun endpoint(port: PortShape): Boolean = port.type.startsWith("op:") || port.type.startsWith(CallPrefix)

    fun island(scope: String): String? = scopePath(scope).firstOrNull { it.id.startsWith(IslandScopePrefix) }?.id

    fun scopePath(scope: String): List<ScopeShape> =
        generateSequence(scopes[scope]) { current -> current.parent?.let(scopes::get) }.toList().reversed()

    fun scopeLabel(scope: String): String = scopePath(scope).drop(1).joinToString(" › ") { it.label }.ifBlank { "root" }

    fun descendants(scope: String): Set<String> =
        generateSequence(listOf(scope)) { frontier -> frontier.flatMap { children[it].orEmpty().map(ScopeShape::id) }.takeIf { it.isNotEmpty() } }
            .flatten().toSet()

    fun containerOf(scope: String): NodeShape? = scopes[scope]?.container?.let(nodes::get)

    fun steps(workflow: String): List<NodeShape> {
        val members = containers[workflow].orEmpty().flatMap { members[it.id].orEmpty() }
        val ids = members.mapTo(HashSet()) { it.id }
        val waits = members.associate { member -> member.id to wiresIn(member.id).map { it.provider }.filterTo(HashSet()) { it in ids && it != member.id } }
        val placed = LinkedHashSet<String>()
        while (placed.size < members.size) {
            val open = members.filter { it.id !in placed }
            placed += (open.firstOrNull { waits.getValue(it.id).all(placed::contains) } ?: open.first()).id
        }
        return placed.map(nodes::getValue)
    }

    fun activeScreens(): Set<String> = shape.scopes.filter { scope -> scope.active && scopePath(scope.id).all { it.active } }
        .mapNotNull { scope -> scope.backStack.lastOrNull()?.let { attachedTo[it] ?: it } }
        .toSet()

    fun backStack(scope: String): List<String> = scopes[scope]?.backStack.orEmpty().mapNotNull { attachedTo[it] ?: it.takeIf(nodes::containsKey) }

    fun routeOf(node: NodeShape): String? = node.route

    val counts: Map<Role, Int> = nodes.values.groupingBy(::role).eachCount()

    private fun internalBinding(id: String, key: String, provides: Boolean): Boolean =
        key in InternalPorts && allNodes[id]?.ports?.any { it.key == key && it.provides == provides && it.internal } == true

    private fun visibleFor(id: String): String? {
        val node = allNodes[id] ?: return null
        return if (!telemetryBindings && node.kind == NodeKind.Route) attachedTo[id] else id
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
