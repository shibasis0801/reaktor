@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.graph.core.Type.Companion.Type
import dev.shibasis.reaktor.graph.visitor.Visitable
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.CoroutineContext
import kotlin.js.JsExport
import kotlin.js.JsName
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

@JsExport
data class KeyType(val key: Key, val type: Type) {
    @JsName("fromString")
    constructor(key: String, type: String): this(Key(key), Type(type))
}

typealias TypedKeyedMap<Value> = MutableMap<Type, MutableMap<Key, Value>>

inline fun<reified Port> TypedKeyedMap<Port>.flattenedValues() = values.flatMap { it.values }

// ------- Provider/Consumer ports at nodes allow edges for interface based communication -------
@JsExport
sealed class Port<Functionality: Any>(
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
class ConsumerPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    var edge: Edge<Functionality>? = null
): Port<Functionality>(owner, key, type) {
    val functionality: Functionality?
        get() = edge?.provider?.impl

    override fun isConnected() = functionality != null

    inline operator fun<R> invoke(fn: Functionality.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(functionality!!)
    }

    @JsExport.Ignore
    suspend inline fun<R> suspended(fn: suspend Functionality.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(functionality!!)
    }
}

@JsExport
class ProviderPort<Functionality: Any>(
    owner: PortCapability,
    key: Key,
    type: Type,
    val impl: Functionality,
    val edges: LinkedHashMap<ConsumerPort<Functionality>, Edge<Functionality>> = linkedMapOf()
): Port<Functionality>(owner, key, type) {
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

typealias PortDelegate<Port> = ReadOnlyProperty<PortCapability, Port>

// ---------------------------- Provider ----------------------------


fun <Functionality: Any> PortCapability.registerProvider(key: Key, type: Type, impl: Functionality): ProviderPort<Functionality> {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ProviderPort(this, key, type, impl) } as ProviderPort<Functionality>
}

fun <Functionality: Any> PortCapability.getProvider(key: Key, type: Type): ProviderPort<Functionality>? {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ProviderPort<Functionality>
}
inline fun <reified Functionality: Any> PortCapability.registerProvider(key: String, impl: Functionality): ProviderPort<Functionality> {
    return registerProvider(Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.provider(impl: Functionality) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerProvider(property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getProvider(key: String): ProviderPort<Functionality>? {
    return getProvider(Key(key), Type<Functionality>())
}

// ---------------------------- Consumer ----------------------------

fun <Functionality: Any> PortCapability.registerConsumer(key: Key, type: Type): ConsumerPort<Functionality> {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, type) } as ConsumerPort<Functionality>
}

fun <Functionality: Any> PortCapability.getConsumer(key: Key, type: Type): ConsumerPort<Functionality>? {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .get(key) as? ConsumerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerConsumer(key: String): ConsumerPort<Functionality> {
    return registerConsumer(Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.consumer() =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerConsumer<Functionality>(property.name)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.getConsumer(key: String): ConsumerPort<Functionality>? {
    return getConsumer(Key(key), Type<Functionality>())
}
