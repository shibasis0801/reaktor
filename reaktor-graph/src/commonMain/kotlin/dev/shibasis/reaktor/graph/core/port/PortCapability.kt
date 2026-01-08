package dev.shibasis.reaktor.graph.core.port

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.CoroutineContext
import kotlin.js.JsExport

@JsExport
sealed class PortEvent(val port: Port<*>) {
    class Created(port: Port<*>): PortEvent(port)
    class Connected(port: Port<*>, val other: Port<*>): PortEvent(port)
    class Disconnected(port: Port<*>, val other: Port<*>): PortEvent(port)
}

@JsExport
interface PortCapability {
    val consumerPorts: TypedKeyedMap<ConsumerPort<Any>>
    val providerPorts: TypedKeyedMap<ProviderPort<Any>>
    val portEvents: SharedFlow<PortEvent>
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
class PortCapabilityImpl(
    context: CoroutineContext? = null,
    override val consumerPorts: TypedKeyedMap<ConsumerPort<Any>> = hashMapOf(),
    override val providerPorts: TypedKeyedMap<ProviderPort<Any>> = hashMapOf(),
    override val portEvents: MutableSharedFlow<PortEvent> = MutableSharedFlow(),
): PortCapability, ConcurrencyCapability by ConcurrencyCapabilityImpl(context) {
    override fun emit(event: PortEvent) {
        launch {
            portEvents.emit(event)
        }
    }
}
