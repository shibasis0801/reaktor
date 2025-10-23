@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

typealias TypedKeyedMap<Contract, UsesContract> = HashMap<KClass<Contract>, LinkedHashMap<String, UsesContract>>
typealias ProducerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ProducerPort<Contract>>
typealias ConsumerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ConsumerPort<Contract>>
typealias EdgeTypedKeyedMap<Contract> = TypedKeyedMap<Contract, Edge<Contract>>

// ------- Producer/Consumer ports at nodes allow edges for interface based communication -------

sealed class Port<Contract: Any>(val owner: PortCapability, val key: String, var edge: Edge<Contract>? = null) {
    fun isConnected(): Boolean = edge != null
}

class ConsumerPort<Contract: Any>(owner: PortCapability, key: String, val kClass: KClass<Contract>): Port<Contract>(owner, key) {
    val contract: Contract?
        get() = edge?.producer?.impl

    inline operator fun<R> invoke(fn: Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }

    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R {
        require(isConnected()) { "Can't invoke functions through unconnected ports." }
        return fn(contract!!)
    }
}
class ProducerPort<Contract: Any>(owner: PortCapability, key: String, val impl: Contract): Port<Contract>(owner, key)


// ---------------------------- PortCapability ----------------------------

interface PortCapability {
    val consumerPorts: ConsumerTypedKeyedMap<*>
    val producerPorts: ProducerTypedKeyedMap<*>
    val edges: EdgeTypedKeyedMap<*>
}

class PortCapabilityImpl(
    override val consumerPorts: ConsumerTypedKeyedMap<*> = hashMapOf(),
    override val producerPorts: ProducerTypedKeyedMap<*> = hashMapOf(),
    override val edges: EdgeTypedKeyedMap<*> = hashMapOf()
): PortCapability



// ---------------------------- Producer ----------------------------

fun <Contract: Any> PortCapability.producer(key: String, impl: Contract, kClass: KClass<Contract>): ProducerPort<Contract> {
    return producerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ProducerPort(this, key, impl) } as ProducerPort<Contract>
}

class ProducerDelegate<Contract : Any>(
    private val impl: Contract,
    private val kClass: KClass<Contract>
) {
    operator fun provideDelegate(
        thisRef: PortCapability,
        property: KProperty<*>
    ): ReadOnlyProperty<PortCapability, ProducerPort<Contract>> {
        val port = thisRef.producer(property.name, impl, kClass)
        return ReadOnlyProperty { _, _ -> port }
    }
}

inline fun <reified Contract: Any> PortCapability.producer(impl: Contract) =
    ProducerDelegate(impl, Contract::class)


fun <Contract: Any> PortCapability.getProducer(key: String, impl: Contract, kClass: KClass<Contract>): ProducerPort<Contract>? {
    return producerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ProducerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getProducer(key: String, impl: Contract): ProducerPort<Contract>? {
    return getProducer(key, impl, Contract::class)
}



// ---------------------------- Consumer ----------------------------

fun <Contract: Any> PortCapability.consumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract> {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, kClass) } as ConsumerPort<Contract>
}

class ConsumerDelegate<Contract : Any>(
    private val kClass: KClass<Contract>
) {
    operator fun provideDelegate(
        thisRef: PortCapability,
        property: KProperty<*>
    ): ReadOnlyProperty<PortCapability, ConsumerPort<Contract>> {
        val port = thisRef.consumer(property.name, kClass)
        return ReadOnlyProperty { _, _ -> port }
    }
}

inline fun <reified Contract: Any> PortCapability.consumer() =
    ConsumerDelegate(Contract::class)

fun <Contract: Any> PortCapability.getConsumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract>? {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.getConsumer(key: String): ConsumerPort<Contract>? {
    return getConsumer(key, Contract::class)
}
