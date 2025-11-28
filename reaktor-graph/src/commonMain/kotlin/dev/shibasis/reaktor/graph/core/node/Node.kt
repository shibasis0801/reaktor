@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.attach
import dev.shibasis.reaktor.graph.core.port.ConsumerPort
import dev.shibasis.reaktor.graph.core.port.PortCapability
import dev.shibasis.reaktor.graph.core.port.PortCapabilityImpl
import dev.shibasis.reaktor.graph.core.port.flattenedValues
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.get
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
sealed class Node(
    val graph: Graph,
    dispatcher: CoroutineDispatcher = graph.coroutineDispatcher,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
):
    Unique, Visitable,
    LifecycleCapability by LifecycleCapabilityImpl(),
    PortCapability by PortCapabilityImpl(
        graph.coroutineScope.coroutineContext
    ),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        graph.coroutineScope.coroutineContext,
        dispatcher
    )
{
    init {
        // todo, risk if subclasses do stuff on lifecycle. lifecycle needs overhaul. 
        graph.attach(this)
    }

    override fun close() {
        consumerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
        invoke<LifecycleCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }

    inline fun <reified T : Any> inject(
        qualifier: String? = null,
        parameters: Map<String, Any?> = emptyMap()
    ): T = graph.diScope.get(qualifier, parameters)

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


