@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

typealias TypedKeyedMap<Contract, UsesContract> = LinkedHashMap<KClass<Contract>, LinkedHashMap<String, UsesContract>>
typealias ProducerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ProducerPort<Contract>>
typealias ConsumerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ConsumerPort<Contract>>
typealias EdgeTypedKeyedMap<Contract> = TypedKeyedMap<Contract, Edge<Contract>>

// ------- Producer/Consumer ports at nodes allow edges for interface based communication -------

sealed class Port<Contract: Any>(val owner: PortCapability, val key: String, var edge: Edge<Contract>? = null)
class ConsumerPort<Contract: Any>(owner: PortCapability, key: String, val kClass: KClass<Contract>): Port<Contract>(owner, key) {
    val contract: Contract?
        get() = edge?.producer?.impl

    inline operator fun<R> invoke(fn: Contract.() -> R): R {
        require(contract != null) { "Can't invoke methods on a null edge." }
        return fn(contract!!)
    }

    suspend inline fun<R> suspended(fn: suspend Contract.() -> R): R {
        require(contract != null) { "Can't invoke methods on a null edge." }
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
    override val consumerPorts: ConsumerTypedKeyedMap<*> = linkedMapOf(),
    override val producerPorts: ProducerTypedKeyedMap<*> = linkedMapOf(),
    override val edges: EdgeTypedKeyedMap<*> = linkedMapOf()
): PortCapability


// ---------------------------- Producer ----------------------------

fun <Contract: Any> PortCapability.newProducer(key: String, impl: Contract, kClass: KClass<Contract>): ProducerPort<Contract> {
    return producerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ProducerPort(this, key, impl) } as ProducerPort<Contract>
}


fun <Contract: Any> PortCapability.producer(key: String, impl: Contract, kClass: KClass<Contract>): ProducerPort<Contract>? {
    return producerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ProducerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.producer(key: String, impl: Contract): ProducerPort<Contract>? {
    return producer(key, impl, Contract::class)
}

class ProducerDelegate<Contract : Any>(
    private val impl: Contract,
    private val kClass: KClass<Contract>
): ReadOnlyProperty<PortCapability, ProducerPort<Contract>> {
    override fun getValue(thisRef: PortCapability, property: KProperty<*>) =
        thisRef.newProducer(property.name, impl, kClass)
}

inline fun <reified Contract: Any> PortCapability.producer(impl: Contract): ReadOnlyProperty<PortCapability, ProducerPort<Contract>> {
    return ProducerDelegate(impl, Contract::class)
}


// ---------------------------- Consumer ----------------------------

fun <Contract: Any> PortCapability.newConsumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract> {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, kClass) } as ConsumerPort<Contract>
}


fun <Contract: Any> PortCapability.consumer(key: String, kClass: KClass<Contract>): ConsumerPort<Contract>? {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .get(key) as? ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.consumer(key: String): ConsumerPort<Contract>? {
    return consumer(key, Contract::class)
}

class ConsumerDelegate<Contract : Any>(
    private val kClass: KClass<Contract>
): ReadOnlyProperty<PortCapability, ConsumerPort<Contract>> {
    override fun getValue(thisRef: PortCapability, property: KProperty<*>) =
        thisRef.newConsumer(property.name, kClass)
}

inline fun <reified Contract: Any> PortCapability.consumer(): ReadOnlyProperty<PortCapability, ConsumerPort<Contract>> {
    return ConsumerDelegate(Contract::class)
}
