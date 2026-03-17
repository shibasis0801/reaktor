package dev.shibasis.reaktor.portgraph.graph

import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortEvent
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
        consumers.keys.first() == providers.keys.first() &&
        consumers.values.first().type == providers.values.first().type
    ) {
        return connect(
            consumers.values.first(),
            providers.values.first()
        ).map { listOf(it) }
    }

    val failures = mutableListOf<String>()
    val edges = mutableListOf<Edge<Any>>()

    consumers.forEach { (key, consumerPort) ->
        val providerPort = providers[key]
        when {
            providerPort == null -> failures += "Missing provider for key='${key.key}' type='${consumerPort.type.type}'"
            consumerPort.type != providerPort.type ->
                failures += "Type mismatch for key='${key.key}': consumer='${consumerPort.type.type}' provider='${providerPort.type.type}'"
            else -> connect(consumerPort, providerPort)
                .onSuccess(edges::add)
                .onFailure { failures += it.message ?: "Failed to connect key='${key.key}'" }
        }
    }

    return if (failures.isEmpty()) {
        Result.success(edges)
    } else {
        Result.failure(IllegalStateException(failures.joinToString(separator = "\n")))
    }
}

private fun connectConsumerProvider(consumerNode: PortCapability, providerNode: PortCapability) {
    val consumerTypes = consumerNode.consumerPorts.keys
    val providerTypes = providerNode.providerPorts.keys
    consumerTypes.intersect(providerTypes)
        .forEach {
            connect(
                consumerNode.consumerPorts[it] ?: mapOf(),
                providerNode.providerPorts[it] ?: mapOf()
            ).getOrThrow()
        }
}


@JsExport
@JsName("connectNode")
fun connect(node1: PortCapability, node2: PortCapability) {
    connectConsumerProvider(node1, node2)
    connectConsumerProvider(node2, node1)
}

infix fun PortCapability.connectWith(other: PortCapability) = connect(this, other)

internal fun <C : Any> disconnectInternal(
    consumerPort: ConsumerPort<C>,
    providerPort: ProviderPort<C>,
) {
    if (consumerPort.edge == null && providerPort.edges[consumerPort] == null) {
        return
    }

    consumerPort.edge = null
    providerPort.edges.remove(consumerPort)
    consumerPort.owner.emit(PortEvent.Disconnected(consumerPort, providerPort))
    providerPort.owner.emit(PortEvent.Disconnected(providerPort, consumerPort))
}

fun <C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>, kClass: KClass<C>) {
    disconnectInternal(consumerPort, providerPort)
}

inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>)
    = disconnect(consumerPort, providerPort, C::class)

inline fun <reified C : Any> disconnect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>)
    = disconnect(consumerPort, providerPort)
