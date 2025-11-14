package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import kotlin.js.JsExport
import kotlin.js.JsName


fun<C: Any> connect(requirerPort: RequirerPort<C>, providerPort: ProviderPort<C>): Result<Unit> {
    if (requirerPort.type != providerPort.type)
        return fail("Incompatible ports: consumer -> ${requirerPort.type}, provider -> ${providerPort.type}")


    val source = requirerPort.owner
    val destination = providerPort.owner

    val edge = Edge(
        source,
        requirerPort,
        destination,
        providerPort
    )

    requirerPort.edge = edge
    providerPort.edges[requirerPort] = edge

    requirerPort.owner.emit(PortEvent.Connected(requirerPort, providerPort))
    providerPort.owner.emit(PortEvent.Connected(providerPort, requirerPort))

    return succeed(Unit)
}

@JsExport
@JsName("connectPort")
fun connectPort(requirerPort: RequirerPort<Any>, providerPort: ProviderPort<Any>)
        = connect(requirerPort, providerPort)

@JsName("connectPorts")
fun connect(
    consumers: Map<Key, RequirerPort<Any>>,
    providers: Map<Key, ProviderPort<Any>>
): Result<Unit> {
    if (
        consumers.size == 1 && providers.size == 1 &&
        consumers.values.first().type == providers.values.first().type
    ) {
        return connect(
            consumers.values.first(),
            providers.values.first()
        )
    }

    consumers.keys
        .intersect(providers.keys)
        .forEach {
            val consumer = consumers[it] as RequirerPort<Any>
            val provider = providers[it] as ProviderPort<Any>
            if (consumer.type == provider.type)
                connect(consumer, provider)
        }

    return succeed(Unit)
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
