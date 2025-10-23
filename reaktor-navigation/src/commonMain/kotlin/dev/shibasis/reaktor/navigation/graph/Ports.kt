@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


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



typealias TypedKeyedMap<Contract, UsesContract> = LinkedHashMap<KClass<Contract>, LinkedHashMap<String, UsesContract>>
typealias ProducerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ProducerPort<Contract>>
typealias ConsumerTypedKeyedMap<Contract> = TypedKeyedMap<Contract, ConsumerPort<Contract>>
typealias EdgeTypedKeyedMap<Contract> = TypedKeyedMap<Contract, Edge<Contract>>

inline fun<reified Contract: Any> ProducerTypedKeyedMap<*>.retrieve(key: String): ProducerPort<Contract>? {
    val map = get(Contract::class) ?: return null
    return map[key] as? ProducerPort<Contract>
}

inline fun<reified Contract: Any> ConsumerTypedKeyedMap<*>.retrieve(key: String): ConsumerPort<Contract>? {
    val map = get(Contract::class) ?: return null
    return map[key] as? ConsumerPort<Contract>
}

inline fun<reified Contract: Any> EdgeTypedKeyedMap<*>.retrieve(key: String): Edge<Contract>? {
    val map = get(Contract::class) ?: return null
    return map[key] as? Edge<Contract>
}


interface PortCapability {
    val consumerPorts: ConsumerTypedKeyedMap<*>
    val producerPorts: ProducerTypedKeyedMap<*>
    val edges: EdgeTypedKeyedMap<*>
}

fun <Contract: Any> PortCapability.produces(key: String, impl: Contract, kClass: KClass<Contract>): ProducerPort<Contract> {
    return producerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ProducerPort(this, key, impl) } as ProducerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.produces(key: String, impl: Contract): ProducerPort<Contract> {
    return produces(key, impl, Contract::class)
}


class ProducesDelegate<Contract : Any>(
    private val impl: Contract,
    private val kClass: KClass<Contract>
): ReadOnlyProperty<PortCapability, ProducerPort<Contract>> {
    override fun getValue(thisRef: PortCapability, property: KProperty<*>) =
        thisRef.produces(property.name, impl, kClass)
}


inline fun <reified Contract: Any> PortCapability.producer(impl: Contract): ReadOnlyProperty<PortCapability, ProducerPort<Contract>> {
    return ProducesDelegate(impl, Contract::class)
}

fun <Contract: Any> PortCapability.consumes(key: String, kClass: KClass<Contract>): ConsumerPort<Contract> {
    return consumerPorts
        .getOrPut(kClass) { linkedMapOf() }
        .getOrPut(key) { ConsumerPort(this, key, kClass) } as ConsumerPort<Contract>
}

inline fun <reified Contract: Any> PortCapability.consumes(key: String): ConsumerPort<Contract> {
    return consumes(key, Contract::class)
}

class ConsumesDelegate<Contract : Any>(
    private val kClass: KClass<Contract>
): ReadOnlyProperty<PortCapability, ConsumerPort<Contract>> {
    override fun getValue(thisRef: PortCapability, property: KProperty<*>) =
        thisRef.consumes(property.name, kClass)
}

inline fun <reified Contract: Any> PortCapability.consumer(): ReadOnlyProperty<PortCapability, ConsumerPort<Contract>> {
    return ConsumesDelegate(Contract::class)
}


inline fun <reified Contract: Any> PortCapability.addEdge(key: String, edge: Edge<Contract>) {
    val map = edges.getOrPut(Contract::class) { linkedMapOf() }
    map[key] = edge
}

class PortCapabilityImpl(
    override val consumerPorts: ConsumerTypedKeyedMap<*> = linkedMapOf(),
    override val producerPorts: ProducerTypedKeyedMap<*> = linkedMapOf(),
    override val edges: EdgeTypedKeyedMap<*> = linkedMapOf()
): PortCapability


inline fun <reified C : Any> connect(consumerPort: ConsumerPort<C>, producerPort: ProducerPort<C>) {
    val source = consumerPort.owner
    val destination = producerPort.owner

    val edge = Edge(
        source,
        consumerPort,
        destination,
        producerPort
    )

    consumerPort.edge = edge
    producerPort.edge = edge

    source.addEdge(consumerPort.key, edge)
}

inline fun <reified C : Any> connect(producerPort: ProducerPort<C>, consumerPort: ConsumerPort<C>)
    = connect(consumerPort, producerPort)


inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, producerPort: ProducerPort<C>) {
    val source = consumerPort.owner

    source.edges[C::class]
        ?.remove(consumerPort.key)

    consumerPort.edge = null
    producerPort.edge = null
}

inline fun <reified C : Any> disconnect(producerPort: ProducerPort<C>, consumerPort: ConsumerPort<C>)
    = disconnect(consumerPort, producerPort)
