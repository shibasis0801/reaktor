package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.navigation.Forward
import dev.shibasis.reaktor.graph.navigation.NavigationCapability
import dev.shibasis.reaktor.graph.navigation.NavigationCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.navigation.Replace
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyException
import dev.shibasis.reaktor.graph.navigation.BackStackEntry
import dev.shibasis.reaktor.graph.navigation.NavCommand
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
open class Graph(
    parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    dependencyAdapter: DependencyAdapter<*> = Feature.Dependency ?: throw DependencyException,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val dependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
): PortGraph<Graph, Node>(id, label),
    LifecycleCapability by LifecycleCapabilityImpl(),
    DependencyCapability by DependencyCapabilityImpl(
        diAdapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = dependencies
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        parentGraph?.coroutineScope?.coroutineContext,
        dispatcher
    ),
    NavigationCapability
{
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
        val edge = navCommand.entry.edge
        val destGraph = edge.destinationGraph
        val container = findContainerForGraph(destGraph)
        if (container != null) {
            container.activateGraphForRoute(edge.end)
            val containerEdge = edge.start.edge(container.route)
            val containerEntry = BackStackEntry<Payload, Unit>(containerEdge, Payload())
            when (navCommand) {
                is Push<*, *> -> navigationImpl.dispatch(Push(containerEntry))
                is Replace<*, *> -> navigationImpl.dispatch(Replace(containerEntry))
            }
            destGraph.dispatch(navCommand as NavCommand)
        } else {
            navigationImpl.dispatch(navCommand as NavCommand)
        }
    }

    init { builder() }

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
                nodes.clear()
            }
        }
    }

    override fun close() {
        transition(Lifecycle.Destroying)
        invoke<DependencyCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
        invoke<LifecycleCapability> { close() }
    }

    override fun toString(): String {
        return "[Graph] label='$label' id='$id' nodes=${nodes.size} stackDepth=${backStack.entries.value.size}, sentinel=$sentinel"
    }
}

fun Graph.attach(node: Node): Result<Unit> {
    if (nodes.find { it.id == node.id } != null) {
        Logger.w("Node ${node.id} is already attached. Ignoring.")
        return fail(ConcurrentModificationException("Node already attached"))
    }

    nodes += node
    node.transition(Lifecycle.Restoring)

    return succeed(Unit)
}

fun Graph.detach(node: Node) {
    node.transition(Lifecycle.Saving)
    nodes.remove(node)
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
                connect(consumer, match)
            }
        }
}

fun<G: Graph> Graph.Graph(builder: (Graph) -> G): G = builder(this)

fun Graph.findContainerForGraph(targetGraph: Graph): ContainerNode? {
    for (container in nodes.filterIsInstance<ContainerNode>()) {
        if (container.graphs.contains(targetGraph)) {
            return container
        }
        for (childGraph in container.graphs) {
            val found = childGraph.findContainerForGraph(targetGraph)
            if (found != null) return found
        }
    }
    return null
}
