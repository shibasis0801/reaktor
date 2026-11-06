package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.ChannelNode
import dev.shibasis.reaktor.graph.ContractNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.node.ExitPort
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.traversal.Members
import dev.shibasis.reaktor.portgraph.traversal.Relation
import dev.shibasis.reaktor.portgraph.traversal.Step
import dev.shibasis.reaktor.portgraph.traversal.Visitable
import dev.shibasis.reaktor.portgraph.traversal.depthFirst
import dev.shibasis.reaktor.service.OperationDescriptor
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.ChannelShape
import dev.shibasis.reaktor.service.contract.ContractShape
import dev.shibasis.reaktor.service.contract.InterceptorShape
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
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
    val definitionDigest: String = "",
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
    val definitionId: String = "",
    val outputs: List<ScopePortShape> = emptyList(),
    val inputs: List<ScopePortShape> = emptyList(),
)

@Serializable
data class ScopePortShape(val name: String, val type: String, val node: String, val port: String)

@JsExport
@Serializable
enum class NodeKind { Route, Screen, Container, Service, Actor, Controller, Interactor, Store, Schedule, External, Workflow, Node }

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
    val definitionId: String = "",
    val access: ServiceAccess? = null,
    val contract: ContractShape? = null,
    val channel: ChannelShape? = null,
    val interceptors: List<InterceptorShape> = emptyList(),
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
    val definitionId: String = "",
)

@Serializable
data class WireShape(
    val consumer: String,
    val consumerPort: String,
    val provider: String,
    val providerPort: String,
    val type: String,
    val definitionId: String = "",
    val sourceId: String = "",
    val condition: String? = null,
)

@Serializable
data class RouteShape(val from: String, val to: String, val exit: String = "")

fun Graph.shape(): GraphShape {
    val scopes = mutableListOf<ScopeShape>()
    val contents = mutableListOf<ShapeContent>()
    val open = ArrayDeque<OpenScope>()

    fun describe(scope: OpenScope) {
        val graph = scope.graph
        val id = graph.id.toString()
        val declared = graph.nodes.let { attached -> attached + attached.filterIsInstance<ContainerNode>().map { it.route } }
            .distinct().withIndex().associate { it.value to it.index }
        val sorted = scope.members.sortedWith(ShapeOrder.thenBy { declared[it] })
        val attachments = sorted.filterIsInstance<RouteNode<*, *>>()
            .flatMap { route -> route.attachedNodes().filterIsInstance<Node>().map { it to route } }
            .toMap()
        sorted.forEach { node ->
            val route = attachments[node]
            scope.content.nodes += NodeShape(
                id = node.id.toString(),
                scope = id,
                label = node.shapeLabel(),
                type = node::class.simpleName ?: "Node",
                kind = node.kind.let { if (route != null && it != NodeKind.Route && it != NodeKind.Container) NodeKind.Screen else it },
                route = (node as? RouteNode<*, *>)?.pattern?.original ?: route?.pattern?.original,
                attachedTo = route?.id?.toString(),
                lifecycle = node.lifecycle.value::class.simpleName,
                ports = node.portShapes(graph),
                attributes = node.attributes(),
                operations = (node as? ContractNode<*>)?.operations.orEmpty(),
                access = (node as? ContractNode<*>)?.api?.access?.takeIf { it.declared },
                contract = (node as? ContractNode<*>)?.api?.shape,
                channel = (node as? ChannelNode<*>)?.shape,
                interceptors = (node as? ContractNode<*>)?.interceptorShapes ?: (node as? ChannelNode<*>)?.interceptorShapes.orEmpty(),
            )
            node.consumerPorts.flattenedValues().forEach { consumer ->
                val edge = consumer.edge ?: return@forEach
                val provider = edge.destination as? Node ?: return@forEach
                scope.content.wires += WireShape(
                    consumer = node.id.toString(),
                    consumerPort = consumer.shapeKey,
                    provider = provider.id.toString(),
                    providerPort = edge.provider.key.key,
                    type = consumer.type.type,
                    condition = (node as? ShapeAttributes)?.condition(consumer.key),
                )
            }
            if (node is RouteNode<*, *>) {
                node.navigationExits().forEach { (exit, target) -> scope.content.routes += RouteShape(node.id.toString(), target.id.toString(), exit) }
            }
        }
    }

    fun enter(graph: Graph, owner: Visitable?) {
        val container = owner as? ContainerNode
        scopes += ScopeShape(
            id = graph.id.toString(),
            label = container?.labelOf(graph) ?: graph.label.ifBlank { "Graph" },
            parent = open.lastOrNull()?.graph?.id?.toString(),
            container = (owner as? Node)?.id?.toString(),
            active = container?.shows(graph) ?: true,
            backStack = graph.backStack.entries.value.mapNotNull { (it.edge.end as? Node)?.id?.toString() },
            outputs = graph.outputs.flatMap { output -> output.ports.map { ScopePortShape(output.name, it.type.type, it.owner.shapeId, it.key.key) } },
            inputs = graph.consumerPorts.flattenedValues().mapNotNull { port ->
                port.edge?.provider?.let { ScopePortShape(port.key.key.ifEmpty { port.type.type }, port.type.type, it.owner.shapeId, it.key.key) }
            },
        )
        open.addLast(OpenScope(graph, ShapeContent().also(contents::add)))
    }

    fun dormant(container: ContainerNode, dormant: GraphShape) {
        val scope = open.last().graph.id.toString()
        val root = dormant.scopes.first { it.parent == null }
        scopes += dormant.scopes.map { shown ->
            if (shown.id == root.id) shown.copy(parent = scope, container = container.id.toString(), active = false, backStack = emptyList())
            else shown.copy(active = false, backStack = emptyList())
        }
        contents += ShapeContent().apply {
            nodes += dormant.nodes.map { it.copy(lifecycle = DormantLifecycle) }
            wires += dormant.wires
            routes += dormant.routes
        }
    }

    depthFirst(InShapeOrder).forEach { step ->
        val element = step.element
        when {
            element is Graph && step is Step.Enter -> enter(element, step.parent)
            element is Graph -> describe(open.removeLast())
            element is Node && step is Step.Enter -> open.last().members.add(element)
            element is ContainerNode -> element.dormant?.let { dormant(element, it) }
        }
    }
    return GraphShape(scopes, contents.flatMap { it.nodes }, contents.flatMap { it.wires }, contents.flatMap { it.routes }).withDefinitionIdentity()
}

private class ShapeContent {
    val nodes = mutableListOf<NodeShape>()
    val wires = mutableListOf<WireShape>()
    val routes = mutableListOf<RouteShape>()
}

private class OpenScope(val graph: Graph, val content: ShapeContent) {
    val members = mutableListOf<Node>()
}

private val ShapeOrder = compareBy<Node>({ it.shapeLabel() }, { it::class.simpleName })

private val InShapeOrder = Relation { element ->
    if (element is Graph) element.members().partition { it is Node }.let { (nodes, graphs) ->
        nodes.filterIsInstance<Node>().sortedWith(ShapeOrder).asSequence() + graphs.asSequence()
    } else Members.next(element)
}

const val DormantLifecycle = "Dormant"

private fun Node.shapeLabel(): String = label.ifBlank { this::class.simpleName ?: "Node" }

private val PortCapability.shapeId: String get() = ((this as? Node)?.id ?: (this as? Graph)?.id)?.toString().orEmpty()

fun Graph.shapes(): Flow<GraphShape> = flow {
    while (true) {
        emit(shape())
        merge(*watched().map { it.drop(1) }.toTypedArray()).first()
    }
}

private val InternalPorts = setOf("routeBinding", "navBinding")

private val ConsumerPort<*>.shapeKey: String get() = (this as? ExitPort)?.exit?.name ?: key.key

private fun Graph.watched(): List<Flow<*>> =
    depthFirst(Members).mapNotNull { it as? Step.Enter }.flatMap { step ->
        when (val element = step.element) {
            is Graph -> listOf(element.structure(), element.backStack.entries)
            is ContainerNode -> element.changes
            else -> emptyList()
        }
    }.toList()

private fun Graph.structure(): Flow<Unit> = callbackFlow {
    val members = nodes.toList()
    val ports: PortEventListener = { trySend(Unit) }
    val stop = onStructure { trySend(Unit) }
    members.forEach { it.addPortEventListener(ports) }
    trySend(Unit)
    awaitClose { stop(); members.forEach { it.removePortEventListener(ports) } }
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
            key = port.shapeKey,
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

interface ShapeAttributes {
    val shapeAttributes: Map<String, String>
    fun condition(port: Key): String? = null
}

private fun Node.attributes(): Map<String, String> = when (this) {
    is ShapeAttributes -> shapeAttributes
    else -> emptyMap()
}
