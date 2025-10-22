package dev.shibasis.reaktor.navigation.graph

import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.navigation.capabilities.UniqueImpl

// Always a directed edge
class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val producer: ProducerPort<Contract>
): Unique by UniqueImpl() {
    inline operator fun<R> invoke(fn: Contract.() -> R) = fn(producer.impl)
    suspend inline fun<R> suspended(fn: suspend Contract.() -> R) = fn(producer.impl)
}
