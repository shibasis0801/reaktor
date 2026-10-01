package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.ServiceNode
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.node.StateInteractor
import dev.shibasis.reaktor.graph.ui.BottomNavigationContainer
import dev.shibasis.reaktor.graph.ui.ChildGraph
import dev.shibasis.reaktor.graph.ui.ComposeContent
import dev.shibasis.reaktor.graph.ui.SessionSlot
import dev.shibasis.reaktor.graph.ui.TabbedContainer
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable

@Serializable
data class GraphShape(
    val scopes: List<ScopeShape>,
    val nodes: List<NodeShape>,
    val wires: List<WireShape>,
    val routes: List<RouteShape>,
)

@Serializable
data class ScopeShape(
    val id: String,
    val label: String,
    val parent: String? = null,
    val container: String? = null,
    val active: Boolean = true,
    val backStack: List<String> = emptyList(),
)

@Serializable
enum class NodeKind { Route, Screen, Container, Service, Actor, Controller, Interactor, Node }

@Serializable
data class NodeShape(
    val id: String,
    val scope: String,
    val label: String,
    val type: String,
    val kind: NodeKind,
    val route: String? = null,
    val attachedTo: String? = null,
    val lifecycle: String? = null,
    val ports: List<PortShape> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
enum class PortWiring { Connected, Injected, Unmatched, Unused }

@Serializable
data class PortShape(
    val key: String,
    val type: String,
    val provides: Boolean,
    val wiring: PortWiring,
    val connections: Int = 0,
    val internal: Boolean = false,
)

@Serializable
data class WireShape(
    val consumer: String,
    val consumerPort: String,
    val provider: String,
    val providerPort: String,
    val type: String,
)

@Serializable
data class RouteShape(val from: String, val to: String)

fun Graph.shape(): GraphShape {
    val scopes = mutableListOf<ScopeShape>()
    val nodes = mutableListOf<NodeShape>()
    val wires = mutableListOf<WireShape>()
    val routes = mutableListOf<RouteShape>()

    fun visit(graph: Graph, container: ContainerNode?) {
        val scope = graph.id.toString()
        scopes += ScopeShape(
            id = scope,
            label = container?.labelOf(graph) ?: graph.label.ifBlank { "Graph" },
            parent = container?.graph?.id?.toString(),
            container = container?.id?.toString(),
            active = container?.shows(graph) ?: true,
            backStack = graph.backStack.entries.value.mapNotNull { (it.edge.end as? Node)?.id?.toString() },
        )
        val members = graph.nodes.toList().let { attached -> (attached + attached.filterIsInstance<ContainerNode>().map { it.route }).distinct() }
        val attachments = members.filterIsInstance<RouteNode<*, *>>()
            .flatMap { route -> route.attachedNodes().filterIsInstance<Node>().map { it to route } }
            .toMap()
        members.forEach { node ->
            val route = attachments[node]
            nodes += NodeShape(
                id = node.id.toString(),
                scope = scope,
                label = node.label.ifBlank { node::class.simpleName ?: "Node" },
                type = node::class.simpleName ?: "Node",
                kind = node.kind(route != null),
                route = (node as? RouteNode<*, *>)?.pattern?.original ?: route?.pattern?.original,
                attachedTo = route?.id?.toString(),
                lifecycle = node.lifecycle.value::class.simpleName,
                ports = node.portShapes(graph),
                attributes = node.attributes(),
            )
            node.consumerPorts.flattenedValues().forEach { consumer ->
                val edge = consumer.edge ?: return@forEach
                val provider = edge.destination as? Node ?: return@forEach
                wires += WireShape(
                    consumer = node.id.toString(),
                    consumerPort = consumer.key.key,
                    provider = provider.id.toString(),
                    providerPort = edge.provider.key.key,
                    type = consumer.type.type,
                )
            }
            if (node is RouteNode<*, *>) {
                node.navigationTargets().forEach { target -> routes += RouteShape(node.id.toString(), target.id.toString()) }
            }
        }
        members.filterIsInstance<ContainerNode>().forEach { child ->
            child.graphs.toList().forEach { visit(it, child) }
            if (child is SessionSlot<*> && child.child.value == null) child.blueprint?.let { dormant ->
                val root = dormant.scopes.first { it.parent == null }
                scopes += dormant.scopes.map { shown ->
                    if (shown.id == root.id) shown.copy(parent = scope, container = child.id.toString(), active = false, backStack = emptyList())
                    else shown.copy(active = false, backStack = emptyList())
                }
                nodes += dormant.nodes.map { it.copy(lifecycle = DormantLifecycle) }
                wires += dormant.wires
                routes += dormant.routes
            }
        }
    }

    visit(this, null)
    return GraphShape(scopes, nodes, wires, routes)
}

const val DormantLifecycle = "Dormant"

fun Graph.shapes(): Flow<GraphShape> = flow {
    while (true) {
        emit(shape())
        merge(*watched().map { it.drop(1) }.toTypedArray()).first()
    }
}

private val InternalPorts = setOf("routeBinding", "navBinding")

private fun Graph.watched(): List<Flow<*>> = buildList {
    add(backStack.entries)
    nodes.toList().filterIsInstance<ContainerNode>().forEach { container ->
        add(container.activeGraphIndex)
        when (container) {
            is SessionSlot<*> -> add(container.child)
            is BottomNavigationContainer -> add(container.selected)
            is TabbedContainer -> add(container.selected)
            else -> Unit
        }
        container.graphs.toList().forEach { addAll(it.watched()) }
    }
}

private fun Node.kind(attached: Boolean): NodeKind = when {
    this is RouteNode<*, *> -> NodeKind.Route
    this is ContainerNode -> NodeKind.Container
    attached || this is ComposeContent -> NodeKind.Screen
    this is ServiceNode -> NodeKind.Service
    this is ActorNode<*> -> NodeKind.Actor
    this is ControllerNode<*> -> NodeKind.Controller
    this is StateInteractor<*> -> NodeKind.Interactor
    else -> NodeKind.Node
}

private fun Node.portShapes(graph: Graph): List<PortShape> =
    providerPorts.flattenedValues().map { port ->
        PortShape(
            key = port.key.key,
            type = port.type.type,
            provides = true,
            wiring = if (port.edges.isEmpty()) PortWiring.Unused else PortWiring.Connected,
            connections = port.edges.size,
            internal = port.key.key in InternalPorts,
        )
    } + consumerPorts.flattenedValues().map { port ->
        val connected = port.isConnected()
        PortShape(
            key = port.key.key,
            type = port.type.type,
            provides = false,
            wiring = when {
                connected -> PortWiring.Connected
                graph.resolvesThroughDi(port.qualifier) -> PortWiring.Injected
                else -> PortWiring.Unmatched
            },
            connections = if (connected) 1 else 0,
            internal = port.key.key in InternalPorts,
        )
    }

private fun ContainerNode.children(): Map<String, ChildGraph>? = when (this) {
    is BottomNavigationContainer -> children
    is TabbedContainer -> children
    else -> null
}

private fun ContainerNode.labelOf(graph: Graph): String =
    children()?.entries?.firstOrNull { it.value.graph == graph }?.let { (key, child) -> child.label.ifBlank { key } }
        ?: graph.label.ifBlank { route.pattern.original.trim('/').ifBlank { this::class.simpleName ?: "Graph" } }

private fun ContainerNode.shows(graph: Graph): Boolean = when (this) {
    is SessionSlot<*> -> child.value == graph
    is BottomNavigationContainer -> children[selected.value]?.graph == graph
    is TabbedContainer -> children[selected.value]?.graph == graph
    else -> activeGraph == graph
}

private fun Node.attributes(): Map<String, String> = when (this) {
    is ServiceNode -> buildMap {
        put("service", serviceLabel)
        service.baseUrl.takeIf { it.isNotBlank() }?.let { put("baseUrl", it) }
    }
    else -> emptyMap()
}
