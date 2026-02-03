package dev.shibasis.reaktor.portgraph.port

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
    private val listeners: MutableList<PortEventListener> = mutableListOf()
): PortCapability {
    override fun addPortEventListener(listener: PortEventListener) {
        listeners.add(listener)
    }

    override fun removePortEventListener(listener: PortEventListener) {
        listeners.remove(listener)
    }

    override fun emit(event: PortEvent) {
        listeners.forEach { it(event) }
    }
}
