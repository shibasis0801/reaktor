package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.graph.disconnect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PortCapabilityIntegrationTest {
    @Test
    fun emitsCreatedConnectedAndDisconnectedEvents() {
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumerEvents = mutableListOf<PortEvent>()
        val providerEvents = mutableListOf<PortEvent>()

        consumerOwner.addPortEventListener(consumerEvents::add)
        providerOwner.addPortEventListener(providerEvents::add)

        val consumer = consumerOwner.registerConsumer<String>("message")
        val provider = providerOwner.registerProvider("message", "hello")

        connect(consumer, provider).getOrThrow()
        disconnect(consumer, provider)

        assertEquals(3, consumerEvents.size)
        assertEquals(3, providerEvents.size)
        assertIs<PortEvent.Created>(consumerEvents[0])
        assertIs<PortEvent.Connected>(consumerEvents[1])
        assertIs<PortEvent.Disconnected>(consumerEvents[2])
        assertIs<PortEvent.Created>(providerEvents[0])
        assertIs<PortEvent.Connected>(providerEvents[1])
        assertIs<PortEvent.Disconnected>(providerEvents[2])
    }

    @Test
    fun connectPortsFailsWhenConsumerKeyHasNoProvider() {
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()

        val consumers = mapOf(Key("left") to (consumerOwner.registerConsumer<String>("left") as dev.shibasis.reaktor.portgraph.port.ConsumerPort<Any>))
        val providers = mapOf(Key("right") to (providerOwner.registerProvider("right", "hello") as dev.shibasis.reaktor.portgraph.port.ProviderPort<Any>))

        assertFailsWith<IllegalStateException> {
            connect(consumers, providers).getOrThrow()
        }
    }
}
