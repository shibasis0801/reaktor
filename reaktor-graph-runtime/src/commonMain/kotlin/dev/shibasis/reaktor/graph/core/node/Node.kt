@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.node.PortNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
sealed class Node(
    override val graph: Graph,
    dispatcher: CoroutineDispatcher = graph.coroutineDispatcher,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    portCapability: PortCapability = PortCapabilityImpl()
): PortNode<Graph>(graph, id, label, portCapability),
    LifecycleCapability,
    ConcurrencyCapability
{
    private val lifecycleCapability = LifecycleCapabilityImpl()
    private val concurrencyCapability = ConcurrencyCapabilityImpl(
        graph.coroutineScope.coroutineContext,
        dispatcher
    )

    open val kind: NodeKind get() = NodeKind.Node

    override val lifecycle get() = lifecycleCapability.lifecycle
    override val coroutineScope get() = concurrencyCapability.coroutineScope
    override val coroutineDispatcher get() = concurrencyCapability.coroutineDispatcher

    override fun close() {
        consumerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
        lifecycleCapability.close()
        concurrencyCapability.close()
    }

    interface Stateful<State> {
        val state: MutableStateFlow<State>
    }

    interface Routable {
        val routeBinding: ConsumerPort<out RouteBinding<out Payload>>
    }

    override fun toString(): String {
        return "[${this::class.simpleName}] label='$label' id='$id' inputs=${consumerPorts.flattenedValues().size} outputs=${providerPorts.flattenedValues().size}"
    }
}

fun<N: Node> Graph.Node(builder: (Graph) -> N): N {
    val node = builder(this)
    attach(node)
    return node
}

inline fun <reified C : Any, reified A : Any, N : Node> Graph.Node(
    configuration: ProviderPort<C>,
    key: String,
    crossinline constructor: (Graph, A) -> N,
    factory: (C) -> A,
): N {
    val replacement = findProvider(Type<A>(), Key(key))
    val implementation = replacement?.impl as? A ?: factory(configuration.impl)
    val node = Node { constructor(it, implementation) }
    connect(node.registerConsumer<C>(configuration.key, configuration.type), configuration).getOrThrow()
    if (replacement != null) {
        connect(node.registerConsumer<Any>(replacement.key, replacement.type), replacement).getOrThrow()
    }
    return node
}
