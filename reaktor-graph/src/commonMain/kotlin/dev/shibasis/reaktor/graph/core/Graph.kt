package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.Forward
import dev.shibasis.reaktor.graph.navigation.NavigationCapability
import dev.shibasis.reaktor.graph.navigation.NavigationCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.BackStackEntry
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyException
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.navigation.NavCommand
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
open class Graph(
    val parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*> = Feature.Dependency ?: throw DependencyException,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val dependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
): PortGraph<Graph, Node>(id, label),
    LifecycleCapability,
    DependencyCapability,
    ConcurrencyCapability,
    NavigationCapability
{
    private val lifecycleCapability = LifecycleCapabilityImpl()
    private val dependencyCapability = DependencyCapabilityImpl(
        diAdapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = dependencies
    )
    private val concurrencyCapability = ConcurrencyCapabilityImpl(
        parentGraph?.let { (it as ConcurrencyCapability).coroutineScope.coroutineContext },
        dispatcher
    )

    override val lifecycle get() = lifecycleCapability.lifecycle
    override val diAdapter get() = dependencyCapability.diAdapter
    override val diScope get() = dependencyCapability.diScope
    override val coroutineScope get() = concurrencyCapability.coroutineScope
    override val coroutineDispatcher get() = concurrencyCapability.coroutineDispatcher

    val sentinel = RouteNode(this, "")

    private val navigationImpl = NavigationCapabilityImpl()
    override val backStack get() = navigationImpl.backStack

    override fun dispatch(navCommand: NavCommand) {
        when (navCommand) {
            is Forward<*, *> -> {
                val edge = navCommand.entry.edge
                if (edge.isCrossGraph) {
                    handleCrossGraphForward(navCommand)
                } else {
                    navigationImpl.dispatch(navCommand)
                }
            }
            else -> navigationImpl.dispatch(navCommand)
        }
    }

    private fun handleCrossGraphForward(navCommand: Forward<*, *>) {
        // Bubble up to the parent that owns the container
        if (parentGraph != null) {
            parentGraph.dispatch(navCommand as NavCommand)
            return
        }

        // We are the root graph — find the container and handle it
        val edge = navCommand.entry.edge
        val destGraph = edge.destinationGraph
        val container = findContainerForGraph(destGraph)

        if (container != null) {
            pushContainerEntry(container, navCommand.entry.payload)
            container.activateGraphForRoute(edge.end)
            destGraph.navigationImpl.dispatch(navCommand)
        } else {
            Logger.w("Cross-graph navigation failed: no container found for graph '${destGraph.label}' (${destGraph.id}).")
        }
    }

    private fun pushContainerEntry(
        container: ContainerNode,
        payload: Payload,
    ) {
        val currentRoute = backStack.entries.value.lastOrNull()?.edge?.end ?: sentinel
        if (currentRoute == container.route) {
            return
        }

        navigationImpl.dispatch(
            Push<Payload>(
                currentRoute.edge(container.route),
                Payload(HashMap(payload.routeParams)),
            ),
        )
    }

    init { builder() }

    override fun attach(node: Node): Boolean {
        if (!super.attach(node)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return false
        }

        node.transition(Lifecycle.Restoring)
        node.transition(Lifecycle.Attaching)
        return true
    }

    override fun detach(node: Node): Boolean {
        if (!nodes.contains(node)) {
            return false
        }

        node.transition(Lifecycle.Saving)
        node.transition(Lifecycle.Destroying)
        node.close()
        return super.detach(node)
    }

    fun<P: Payload> addRoot(routeNode: RouteNode<P, *>, payload: P) {
        val edge = sentinel.edge(routeNode)
        dispatch(Push(
            edge, payload
        ))
    }

    override fun onTransition(
        previous: Lifecycle,
        next: Lifecycle
    ) {
        val transitionNodes = { nodes.forEach { it.transition(next) } }

        when (next) {
            Lifecycle.Created -> transitionNodes()
            Lifecycle.Restoring -> transitionNodes()
            Lifecycle.Attaching -> { /* hook if needed */ }
            Lifecycle.Saving -> transitionNodes()
            Lifecycle.Destroying -> {
                nodes.toList().forEach { detach(it) }
            }
        }
    }

    override fun close() {
        transition(Lifecycle.Destroying)
        dependencyCapability.close()
        concurrencyCapability.close()
        lifecycleCapability.close()
    }

    override fun toString(): String {
        return "[Graph] label='$label' id='$id' nodes=${nodes.size} stackDepth=${backStack.entries.value.size}, sentinel=$sentinel"
    }
}

inline fun <reified Functionality : Any> Node.exposePort(port: ProviderPort<Functionality>) {
    graph.diAdapter.register(
        scope = graph.diScope,
        instance = port,
        type = ProviderPort::class,
        qualifier = port.qualifier
    )
}

fun Graph.autoWire() {
    val localProviders = nodes
        .flatMap { node -> node.providerPorts.flattenedValues() }
        .groupBy { it.type }

    nodes.flatMap { it.consumerPorts.flattenedValues() }
        .filter { !it.isConnected() }
        .forEach { consumer ->
            val localCandidates = localProviders[consumer.type] ?: emptyList()
            var match = if (consumer.key.key.isEmpty()) {
                if (localCandidates.size == 1) localCandidates.first()
                else localCandidates.find { it.key.key.isEmpty() }
            } else {
                localCandidates.find { it.key == consumer.key }
            }

            if (match == null) {
                val portFromDI = try {
                    diScope.get(ProviderPort::class, consumer.qualifier)
                } catch (e: Exception) {
                    null
                }

                if (portFromDI != null) {
                    @Suppress("UNCHECKED_CAST")
                    match = portFromDI as ProviderPort<Any>
                }
            }

            if (match != null) {
                connect(consumer, match).getOrThrow()
            }
        }
}

fun Graph.unconnectedConsumers() = nodes
    .flatMap { it.consumerPorts.flattenedValues() }
    .filterNot { it.isConnected() }

fun Graph.requireFullyWired() {
    val unconnected = unconnectedConsumers()
    require(unconnected.isEmpty()) {
        buildString {
            append("Graph '$label' has unconnected consumer ports:")
            unconnected.forEach { port ->
                append("\n- key='${port.key.key}' type='${port.type.type}' owner='${port.owner}'")
            }
        }
    }
}

fun<G: Graph> Graph.Graph(builder: (Graph) -> G): G = builder(this)

fun Graph.findContainerForGraph(
    targetGraph: Graph,
    visited: MutableSet<Graph> = mutableSetOf()
): ContainerNode? {
    if (!visited.add(this)) return null
    for (container in nodes.filterIsInstance<ContainerNode>()) {
        if (container.graphs.contains(targetGraph)) {
            return container
        }
        for (childGraph in container.graphs) {
            val found = childGraph.findContainerForGraph(targetGraph, visited)
            if (found != null) return found
        }
    }
    return null
}
