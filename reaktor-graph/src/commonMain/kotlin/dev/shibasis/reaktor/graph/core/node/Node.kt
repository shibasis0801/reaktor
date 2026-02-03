@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.attach
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
    LifecycleCapability by LifecycleCapabilityImpl(),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        graph.coroutineScope.coroutineContext,
        dispatcher
    )
{
    override fun close() {
        consumerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
        invoke<LifecycleCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
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
