package dev.shibasis.reaktor.navigation.graph

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


inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, producerPort: ProducerPort<C>) {
    val source = consumerPort.owner

    source.edges[C::class]
        ?.remove(consumerPort.key)

    consumerPort.edge = null
    producerPort.edge = null
}

inline fun <reified C : Any> disconnect(producerPort: ProducerPort<C>, consumerPort: ConsumerPort<C>)
        = disconnect(consumerPort, producerPort)
