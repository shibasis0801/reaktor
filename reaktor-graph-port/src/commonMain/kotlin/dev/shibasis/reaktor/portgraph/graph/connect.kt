package dev.shibasis.reaktor.portgraph.graph

import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.reflect.KClass


fun<C: Any> connect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>): Result<Edge<C>> {
    if (consumerPort.type != providerPort.type) {
        val error = "Incompatible ports: consumer -> ${consumerPort.type}, provider -> ${providerPort.type}"
        return Result.failure(IllegalArgumentException(error))
    }

    if (consumerPort.isConnected()) {
        val error = "Consumer is already connected, $consumerPort"
        return Result.failure(IllegalStateException(error))
    }

    val source = consumerPort.owner
    val destination = providerPort.owner

    return Result.success(
        Edge(
            source,
            consumerPort,
            destination,
            providerPort
        )
    )
}

fun<C: Any> connect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>) = connect(consumerPort, providerPort)

@JsExport
@JsName("connectPort")
fun connectPort(consumerPort: ConsumerPort<Any>, providerPort: ProviderPort<Any>)
        = connect(consumerPort, providerPort)

@Suppress("UNCHECKED_CAST")
@JsName("connectPorts")
fun connect(
    consumers: Map<Key, ConsumerPort<Any>>,
    providers: Map<Key, ProviderPort<Any>>
): Result<List<Edge<Any>>> {
    if (
        consumers.size == 1 && providers.size == 1 &&
        consumers.values.first().type == providers.values.first().type
    ) {
        return connect(
            consumers.values.first(),
            providers.values.first()
        ).map { listOf(it) }
    }

    val edges = consumers.keys
        .intersect(providers.keys)
        .mapNotNull {
            val consumer = consumers[it] as ConsumerPort<Any>
            val provider = providers[it] as ProviderPort<Any>
            if (consumer.type == provider.type)
                connect(consumer, provider).getOrNull()
            else null
        }

    return Result.success(edges)
}

private fun connectConsumerProvider(consumerNode: PortCapability, providerNode: PortCapability) {
    val consumerTypes = consumerNode.consumerPorts.keys
    val providerTypes = providerNode.providerPorts.keys
    consumerTypes.intersect(providerTypes)
        .forEach {
            connect(
                consumerNode.consumerPorts[it] ?: mapOf(),
                providerNode.providerPorts[it] ?: mapOf()
            )
        }
}


@JsExport
@JsName("connectNode")
fun connect(node1: PortCapability, node2: PortCapability) {
    connectConsumerProvider(node1, node2)
    connectConsumerProvider(node2, node1)
}

infix fun PortCapability.connectWith(other: PortCapability) = connect(this, other)

fun <C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>, kClass: KClass<C>) {
    consumerPort.edge = null
    providerPort.edges.remove(consumerPort)
}

inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>)
    = disconnect(consumerPort, providerPort, C::class)

inline fun <reified C : Any> disconnect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>)
    = disconnect(consumerPort, providerPort)
