@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.graph

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.navigation.capabilities.UniqueImpl
import kotlin.reflect.KClass

// Always a directed edge
class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl()


fun <C : Any> connect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>, kClass: KClass<C>) {
    val source = consumerPort.owner
    val destination = providerPort.owner

    val edge = Edge(
        source,
        consumerPort,
        destination,
        providerPort
    )

    consumerPort.edge = edge
    providerPort.edges[consumerPort] = edge

    consumerPort.owner.emit(PortEvent.Connected(consumerPort, providerPort))
    providerPort.owner.emit(PortEvent.Connected(providerPort, consumerPort))
}

inline fun <reified C : Any> connect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>)
        = connect(consumerPort, providerPort, C::class)

inline fun <reified C : Any> connect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>)
        = connect(consumerPort, providerPort, C::class)

fun <C: Any> connect(
    consumers: Map<String, ConsumerPort<C>>,
    providers: Map<String, ProviderPort<C>>,
    kClass: KClass<C>
) {
    if (consumers.size == 1 && providers.size == 1) {
        connect(
            consumers.values.first(),
            providers.values.first(),
            kClass
        )
        return
    }

    consumers.keys
        .intersect(providers.keys)
        .forEach {
            val consumer = consumers[it] as ConsumerPort<C>
            val provider = providers[it] as ProviderPort<C>
            connect(consumer, provider, kClass)
        }
}

inline fun <reified C: Any> connect(
    consumers: Map<String, ConsumerPort<C>>,
    providers: Map<String, ProviderPort<C>>
) = connect(consumers, providers, C::class)

fun <C : Any> connect(
    node1: PortCapability,
    node2: PortCapability,
    kClass: KClass<C>
): Result<Unit> {
    val node1Consumers = node1.consumerPorts[kClass]
    val node1Providers = node1.providerPorts[kClass]

    val node2Consumers = node2.consumerPorts[kClass]
    val node2Providers = node2.providerPorts[kClass]

    var result = fail<Unit>(IllegalArgumentException("Unable to find compatible ports"))

    if (node1Consumers != null && node2Providers != null) {
        connect(
            node1Consumers as Map<String, ConsumerPort<C>>,
            node2Providers as Map<String, ProviderPort<C>>,
            kClass
        )
        result = succeed(Unit)
    }

    if (node2Consumers != null && node1Providers != null) {
        connect(
            node2Consumers as Map<String, ConsumerPort<C>>,
            node1Providers as Map<String, ProviderPort<C>>,
            kClass
        )
        result = succeed(Unit)
    }

    return result
}

inline fun <reified C : Any> connect(node1: PortCapability, node2: PortCapability) =
    connect(node1, node2, C::class)

fun connect(node1: PortCapability, node2: PortCapability) {
    val node1Consumers = node1.consumerPorts.keys
    val node1Providers = node1.providerPorts.keys

    val node2Consumers = node2.consumerPorts.keys
    val node2Providers = node2.providerPorts.keys

    node1Consumers
        .intersect(node2Providers)
        .forEach { kClass ->
            connect(node1, node2, kClass)
        }

    node2Consumers
        .intersect(node1Providers)
        .forEach { kClass ->
            connect(node1, node2, kClass)
        }
}

infix fun PortCapability.connectWith(other: PortCapability) = connect(this, other)

inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>) {
    consumerPort.edge = null
    providerPort.edges.remove(consumerPort)
}

inline fun <reified C : Any> disconnect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>)
        = disconnect(consumerPort, providerPort)
