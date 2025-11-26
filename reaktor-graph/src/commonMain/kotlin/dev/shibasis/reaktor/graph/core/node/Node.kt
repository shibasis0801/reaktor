@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import dev.shibasis.reaktor.graph.core.attach
import dev.shibasis.reaktor.graph.core.port.PortCapability
import dev.shibasis.reaktor.graph.core.port.PortCapabilityImpl
import dev.shibasis.reaktor.graph.core.port.flattenedValues
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
sealed class Node(
    val graph: dev.shibasis.reaktor.graph.core.Graph,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
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
        graph.attach(this)
    }

    override fun close() {
        requirerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
        invoke<LifecycleCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }
}


