@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.portgraph.edge

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.UniqueImpl
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import kotlin.js.JsExport

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
