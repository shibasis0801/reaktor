package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.ServiceNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.service.OperationDescriptor
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@Serializable
data class GraphShape(
    val scopes: List<ScopeShape>,
    val nodes: List<NodeShape>,
    val wires: List<WireShape>,
    val routes: List<RouteShape>,
)

@Serializable
data class IslandShape(val island: String, val runtime: String, val graph: GraphShape, val objects: List<IslandShape> = emptyList())

@Serializable
data class ScopeShape(
    val id: String,
    val label: String,
    val parent: String? = null,
    val container: String? = null,
    val active: Boolean = true,
    val backStack: List<String> = emptyList(),
)

@JsExport
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
    val operations: List<OperationDescriptor> = emptyList(),
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
                label = node.label.ifBlank { node.contractLabel() ?: node::class.simpleName ?: "Node" },
                type = node::class.simpleName ?: "Node",
                kind = node.kind.let { if (route != null && it != NodeKind.Route && it != NodeKind.Container) NodeKind.Screen else it },
                route = (node as? RouteNode<*, *>)?.pattern?.original ?: route?.pattern?.original,
                attachedTo = route?.id?.toString(),
                lifecycle = node.lifecycle.value::class.simpleName,
                ports = node.portShapes(graph),
                attributes = node.attributes(),
                operations = (node as? ServiceNode)?.service?.operations.orEmpty(),
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
            child.dormant?.let { dormant ->
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
        addAll(container.changes)
        container.graphs.toList().forEach { addAll(it.watched()) }
    }
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

private fun Node.contractLabel(): String? =
    (this as? ServiceNode)?.takeIf { it::class == ServiceNode::class }?.let { node -> node.service.contract.id.ifBlank { node.serviceLabel } }

private fun Node.attributes(): Map<String, String> = when (this) {
    is ServiceNode -> buildMap {
        put("service", serviceLabel)
        service.baseUrl.takeIf { it.isNotBlank() }?.let { put("baseUrl", it) }
        service.contract.id.takeIf { it.isNotBlank() }?.let {
            put("contract", it)
            put("version", service.contract.version.toString())
        }
        put("role", if (service.handlers.isNotEmpty() && service.handlers.all { it.phase == ServiceExecutionPhase.CLIENT }) "client" else "server")
    }
    else -> emptyMap()
}
