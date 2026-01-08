@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core.edge

import dev.shibasis.reaktor.graph.capabilities.Unique
import dev.shibasis.reaktor.graph.capabilities.UniqueImpl
import dev.shibasis.reaktor.graph.core.port.PortCapability
import dev.shibasis.reaktor.graph.core.port.PortEvent
import dev.shibasis.reaktor.graph.core.port.ProviderPort
import dev.shibasis.reaktor.graph.core.port.ConsumerPort
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlin.js.JsExport


/*
Must allow
1. Accessing Functionality from another node
2. Navigating to another node (screens, ui)
3. DAG execution dependencies forward/backward
4. Remote communication over multiple transports using Service Abstraction
5. decorate contract with permissions / arrow-kt resilience / circuit breakers / retries, etc (maybe as middleware for invoke/suspended)
*/
@JsExport
open class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl(), Visitable {
    init {
        require(!consumer.isConnected()) { "Consumer is already connected." }
        consumer.edge = this
        provider.edges[consumer] = this

        source.emit(PortEvent.Connected(consumer, provider))
        destination.emit(PortEvent.Connected(provider, consumer))
    }

    inline operator fun<R> invoke(fn: Contract.() -> R): R = provider.invoke(fn)
    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R = provider.suspended(fn)

    override fun toString(): String {
        val src = (source as? Unique)?.let { "${it.label} (${it.id})" } ?: "Unknown"
        val dest = (destination as? Unique)?.let { "${it.label} (${it.id})" } ?: "Unknown"
        return "[Edge] $id: $src.${consumer.key} -> $dest.${provider.key}"
    }
}

