@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.CoroutineContext
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

typealias TypedKeyedMap<Contract, UsesContract> = HashMap<KClass<Contract>, LinkedHashMap<String, UsesContract>>
typealias ProviderTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ProviderPort<Contract>>
typealias ConsumerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ConsumerPort<Contract>>

// ------- Provider/Consumer ports at nodes allow edges for interface based communication -------

sealed class Port<Contract: Any>(val owner: PortCapability, val key: String) {
    abstract fun isConnected(): Boolean
}

class ConsumerPort<Contract: Any>(owner: PortCapability, key: String, val kClass: KClass<Contract>, var edge: Edge<Contract>? = null): Port<Contract>(owner, key) {
    val contract: Contract?
        get() = edge?.provider?.impl

    override fun isConnected() = contract != null

    inline operator fun<R> invoke(fn: Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }

    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }
}
class ProviderPort<Contract: Any>(
    owner: PortCapability, key: String, val impl: Contract, val edges: LinkedHashMap<ConsumerPort<Contract>, Edge<Contract>> = linkedMapOf()
): Port<Contract>(owner, key) {
    override fun isConnected() = edges.isNotEmpty()
}


// ---------------------------- PortCapability ----------------------------

sealed class PortEvent(val port: Port<*>) {
    class Created(port: Port<*>): PortEvent(port)
    class Connected(port: Port<*>, val other: Port<*>): PortEvent(port)
    class Disconnected(port: Port<*>, val other: Port<*>): PortEvent(port)
}

interface PortCapability {
    val consumerPorts: ConsumerTypedKeyedMap<*>
    val providerPorts: ProviderTypedKeyedMap<*>
    val portEvents: SharedFlow<PortEvent>
    fun emit(event: PortEvent)
}

class PortCapabilityImpl(
    context: CoroutineContext? = null,
    override val consumerPorts: ConsumerTypedKeyedMap<*> = hashMapOf(),
    override val providerPorts: ProviderTypedKeyedMap<*> = hashMapOf(),
    override val portEvents: MutableSharedFlow<PortEvent> = MutableSharedFlow(),
): PortCapability, ConcurrencyCapability by ConcurrencyCapabilityImpl(context) {
    override fun emit(event: PortEvent) {
        launch {
            portEvents.emit(event)
        }
    }
}

typealias PortDelegate<Port> = ReadOnlyProperty<PortCapability, Port>

// ---------------------------- Provider ----------------------------

fun <Contract: Any> PortCapability.provider(key: String, impl: Contract, kClass: KClass<Contract>): ProviderPort<Contract> {
    return providerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ProviderPort(this, key, impl) } as ProviderPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.provider(key: String, impl: Contract): ProviderPort<Contract> {
    return provider(key, impl, Contract::class)
}

inline fun <reified Contract: Any> PortCapability.provider(impl: Contract) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Contract>>> { thisRef, property ->
        val port = thisRef.provider(property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

fun <Contract: Any> PortCapability.getProvider(key: String, kClass: KClass<Contract>): ProviderPort<Contract>? {
    return providerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ProviderPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getProvider(key: String): ProviderPort<Contract>? {
    return getProvider(key, Contract::class)
}

// ---------------------------- Consumer ----------------------------

fun <Contract: Any> PortCapability.consumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract> {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, kClass) } as ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.consumer(key: String): ConsumerPort<Contract> {
    return consumer(key, Contract::class)
}

inline fun <reified Contract: Any> PortCapability.consumer() =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Contract>>> { thisRef, property ->
        val port = thisRef.consumer<Contract>(property.name)
        ReadOnlyProperty { _, _ -> port }
    }

fun <Contract: Any> PortCapability.getConsumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract>? {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getConsumer(key: String): ConsumerPort<Contract>? {
    return getConsumer(key, Contract::class)
}
