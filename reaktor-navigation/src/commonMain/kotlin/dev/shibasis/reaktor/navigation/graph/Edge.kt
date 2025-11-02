@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.navigation.capabilities.UniqueImpl
import dev.shibasis.reaktor.navigation.graph.Type.Companion.Type
import dev.shibasis.reaktor.navigation.visitor.Visitable
import kotlin.js.JsExport
import kotlin.js.JsName

// Always a directed edge
@JsExport
class Edge<Contract: Any>(
    val source: PortCapability,
    val consumer: ConsumerPort<Contract>,
    val destination: PortCapability,
    val provider: ProviderPort<Contract>
): Unique by UniqueImpl(), Visitable

fun<C: Any> connect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>): Result<Unit> {
    if (consumerPort.type != providerPort.type)
        return fail("Incompatible ports: consumer -> ${consumerPort.type}, provider -> ${providerPort.type}")


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

    return succeed(Unit)
}

@JsExport
@JsName("connectPort")
fun connectPort(consumerPort: ConsumerPort<Any>, providerPort: ProviderPort<Any>)
    = connect(consumerPort, providerPort)

@JsName("connectPorts")
fun connect(
    consumers: Map<Key, ConsumerPort<Any>>,
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
            val consumer = consumers[it] as ConsumerPort<Any>
            val provider = providers[it] as ProviderPort<Any>
            if (consumer.type == provider.type)
                connect(consumer, provider)
        }

    return succeed(Unit)
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

inline fun <reified C : Any> disconnect(consumerPort: ConsumerPort<C>, providerPort: ProviderPort<C>) {
    consumerPort.edge = null
    providerPort.edges.remove(consumerPort)
}

inline fun <reified C : Any> disconnect(providerPort: ProviderPort<C>, consumerPort: ConsumerPort<C>)
        = disconnect(consumerPort, providerPort)
