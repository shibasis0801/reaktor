@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.graph

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.navigation.capabilities.UniqueImpl

// Always a directed edge
class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val producer: ProducerPort<Contract>
): Unique by UniqueImpl()


inline fun <reified Contract: Any> PortCapability.addEdge(key: String, edge: Edge<Contract>) {
    val map = edges.getOrPut(Contract::class) { linkedMapOf() }
    map[key] = edge
}

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

inline fun <reified C: Any> connect(
    consumers: Map<String, ConsumerPort<C>>,
    producers: Map<String, ProducerPort<C>>
) {
    if (consumers.size == 1 && producers.size == 1) {
        connect(
            consumers.values.first(),
            producers.values.first()
        )
        return
    }

    consumers.keys
        .intersect(producers.keys)
        .forEach {
            val consumer = consumers[it] as ConsumerPort<C>
            val producer = producers[it] as ProducerPort<C>
            connect(consumer, producer)
        }
}

inline fun <reified C : Any> connect(node1: PortCapability, node2: PortCapability): Result<Unit> {
    val node1Consumers = node1.consumerPorts[C::class]
    val node1Producers = node1.producerPorts[C::class]

    val node2Consumers = node2.consumerPorts[C::class]
    val node2Producers = node2.producerPorts[C::class]

    var result = fail<Unit>(IllegalArgumentException("Unable to find compatible ports"))

    if (node1Consumers != null && node2Producers != null) {
        connect(
            node1Consumers as Map<String, ConsumerPort<C>>,
            node2Producers as Map<String, ProducerPort<C>>,
        )
        result = succeed(Unit)
    }

    if (node2Consumers != null && node1Producers != null) {
        connect(
            node2Consumers as Map<String, ConsumerPort<C>>,
            node1Producers as Map<String, ProducerPort<C>>,
        )
        result = succeed(Unit)
    }

    return result
}

inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, producerPort: ProducerPort<C>) {
    val source = consumerPort.owner

    source.edges[C::class]
        ?.remove(consumerPort.key)

    consumerPort.edge = null
    producerPort.edge = null
}

inline fun <reified C : Any> disconnect(producerPort: ProducerPort<C>, consumerPort: ConsumerPort<C>)
        = disconnect(consumerPort, producerPort)
