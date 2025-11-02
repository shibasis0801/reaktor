@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.navigation.graph.Type.Companion.Type
import dev.shibasis.reaktor.navigation.visitor.Visitable
import dev.shibasis.reaktor.navigation.visitor.Visitor
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.CoroutineContext
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.jvm.JvmInline
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

@JsExport
data class Key(val key: String)

@JsExport
data class Type(val type: String, val kClass: KClass<*>? = null) {
    companion object {
        val _sequence = atomic(0)
        @JsExport.Ignore
        inline fun<reified T> Type() = Type(
            T::class.simpleName ?: "anonymous_${_sequence.getAndIncrement()}",
            T::class
        )
    }
}

typealias TypedKeyedMap<Value> = MutableMap<Type, MutableMap<Key, Value>>

inline fun<reified Port> TypedKeyedMap<Port>.flattenedValues() = values.flatMap { it.values }

// ------- Provider/Consumer ports at nodes allow edges for interface based communication -------
@JsExport
sealed class Port<Contract: Any>(
    val owner: PortCapability,
    val key: Key,
    val type: Type
): Visitable {
    abstract fun isConnected(): Boolean
    val node: Node
        get() = owner as Node

    @JsName("createWithStrings")
    constructor(owner: PortCapability, key: String, type: String)
            : this(owner, Key(key), Type(type))
}

@JsExport
class ConsumerPort<Contract: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    var edge: Edge<Contract>? = null
): Port<Contract>(owner, key, type) {
    val contract: Contract?
        get() = edge?.provider?.impl

    override fun isConnected() = contract != null

    inline operator fun<R> invoke(fn: Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }

    @JsExport.Ignore
    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }
}

@JsExport
class ProviderPort<Contract: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    val impl: Contract,
    val edges: LinkedHashMap<ConsumerPort<Contract>, Edge<Contract>> = linkedMapOf()
): Port<Contract>(owner, key, type) {
    override fun isConnected() = edges.isNotEmpty()
}


// ---------------------------- PortCapability ----------------------------

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

typealias PortDelegate<Port> = ReadOnlyProperty<PortCapability, Port>

// ---------------------------- Provider ----------------------------
@JsExport
fun <Contract: Any> PortCapability.provider(key: Key, type: Type, impl: Contract): ProviderPort<Contract> {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ProviderPort(this, key, type, impl) } as ProviderPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.provider(key: String, impl: Contract): ProviderPort<Contract> {
    return provider(Key(key), Type<Contract>(), impl)
}

inline fun <reified Contract: Any> PortCapability.provider(impl: Contract) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Contract>>> { thisRef, property ->
        val port = thisRef.provider(property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

@JsExport
fun <Contract: Any> PortCapability.getProvider(key: Key, type: Type): ProviderPort<Contract>? {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ProviderPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getProvider(key: String): ProviderPort<Contract>? {
    return getProvider(Key(key), Type<Contract>())
}

// ---------------------------- Consumer ----------------------------
@JsExport
fun <Contract: Any> PortCapability.consumer(key: Key, type: Type): ConsumerPort<Contract> {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, type) } as ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.consumer(key: String): ConsumerPort<Contract> {
    return consumer(Key(key), Type<Contract>())
}

inline fun <reified Contract: Any> PortCapability.consumer() =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Contract>>> { thisRef, property ->
        val port = thisRef.consumer<Contract>(property.name)
        ReadOnlyProperty { _, _ -> port }
    }

@JsExport
fun <Contract: Any> PortCapability.getConsumer(key: Key, type: Type): ConsumerPort<Contract>? {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getConsumer(key: String): ConsumerPort<Contract>? {
    return getConsumer(Key(key), Type<Contract>())
}
