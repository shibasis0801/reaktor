package dev.shibasis.reaktor.portgraph.port

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlin.js.JsExport

@JsExport
sealed class PortEvent(val port: Port<*>) {
    class Created(port: Port<*>): PortEvent(port)
    class Connected(port: Port<*>, val other: Port<*>): PortEvent(port)
    class Disconnected(port: Port<*>, val other: Port<*>): PortEvent(port)
}

typealias PortEventListener = (PortEvent) -> Unit

@JsExport
interface PortCapability {
    val consumerPorts: TypedKeyedMap<ConsumerPort<Any>>
    val providerPorts: TypedKeyedMap<ProviderPort<Any>>
    fun addPortEventListener(listener: PortEventListener)
    fun removePortEventListener(listener: PortEventListener)
    fun emit(event: PortEvent)

    fun <Functionality: Any> registerProvider(keyType: KeyType, impl: Functionality): ProviderPort<Functionality> {
        return registerProvider(keyType.key, keyType.type, impl)
    }

    fun <Functionality: Any> getProvider(keyType: KeyType): ProviderPort<Functionality>? {
        return getProvider(keyType.key, keyType.type)
    }

    fun <Functionality: Any> registerConsumer(keyType: KeyType): ConsumerPort<Functionality> {
        return registerConsumer(keyType.key, keyType.type)
    }

    fun <Functionality: Any> getConsumer(keyType: KeyType): ConsumerPort<Functionality>? {
        return getConsumer(keyType.key, keyType.type)
    }
}

@JsExport
open class PortCapabilityImpl(
    override val consumerPorts: TypedKeyedMap<ConsumerPort<Any>> = hashMapOf(),
    override val providerPorts: TypedKeyedMap<ProviderPort<Any>> = hashMapOf(),
    listeners: List<PortEventListener> = emptyList()
): PortCapability {
    /**
     * Replaced whole on every change, never edited.
     *
     * A node's listeners are added from its own coroutines, on whatever threads those run on, and
     * called from the thread wiring the graph. As a list edited in place this was read while it was
     * being written: a listener that was null, an index past the end, and — the quiet one — a
     * listener that was added and is not there. An immutable list swapped atomically has none of
     * those states for another thread to see.
     *
     * It also makes [emit] safe against the listener that removes itself while being called, which
     * is the common shape for "wake me when this port is connected": what is being iterated is the
     * list as it was, whatever has been swapped in since.
     */
    private val listeners = atomic(listeners.toList())

    override fun addPortEventListener(listener: PortEventListener) {
        listeners.update { it + listener }
    }

    override fun removePortEventListener(listener: PortEventListener) {
        listeners.update { it - listener }
    }

    override fun emit(event: PortEvent) {
        listeners.value.forEach { it(event) }
    }
}
