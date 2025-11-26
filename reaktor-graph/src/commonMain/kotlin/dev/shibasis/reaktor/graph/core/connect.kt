package dev.shibasis.reaktor.graph.core

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.graph.core.edge.Edge
import dev.shibasis.reaktor.graph.core.port.Key
import dev.shibasis.reaktor.graph.core.port.PortCapability
import dev.shibasis.reaktor.graph.core.port.ProviderPort
import dev.shibasis.reaktor.graph.core.port.RequirerPort
import kotlin.js.JsExport
import kotlin.js.JsName


fun<C: Any> connect(requirerPort: RequirerPort<C>, providerPort: ProviderPort<C>): Result<Edge<C>> {
    if (requirerPort.type != providerPort.type) {
        val error = "Incompatible ports: consumer -> ${requirerPort.type}, provider -> ${providerPort.type}"
        Logger.e { error }
        return fail(error)
    }

    val source = requirerPort.owner
    val destination = providerPort.owner

    return succeed(
        Edge(
            source,
            requirerPort,
            destination,
            providerPort
        )
    )
}

fun<C: Any> connect(providerPort: ProviderPort<C>, requirerPort: RequirerPort<C>) = connect(requirerPort, providerPort)

@JsExport
@JsName("connectPort")
fun connectPort(requirerPort: RequirerPort<Any>, providerPort: ProviderPort<Any>)
        = connect(requirerPort, providerPort)

@JsName("connectPorts")
fun connect(
    consumers: Map<Key, RequirerPort<Any>>,
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
            val consumer = consumers[it] as RequirerPort<Any>
            val provider = providers[it] as ProviderPort<Any>
            if (consumer.type == provider.type)
                connect(consumer, provider).getOrNull()
            else null
        }

    return succeed(edges)
}

private fun connectConsumerProvider(consumerNode: PortCapability, providerNode: PortCapability) {
    val consumerTypes = consumerNode.requirerPorts.keys
    val providerTypes = providerNode.providerPorts.keys
    consumerTypes.intersect(providerTypes)
        .forEach {
            connect(
                consumerNode.requirerPorts[it] ?: mapOf(),
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

inline fun <reified C : Any> disconnect(requirerPort: RequirerPort<C>, providerPort: ProviderPort<C>) {
    requirerPort.edge = null
    providerPort.edges.remove(requirerPort)
}

inline fun <reified C : Any> disconnect(providerPort: ProviderPort<C>, requirerPort: RequirerPort<C>)
        = disconnect(requirerPort, providerPort)
