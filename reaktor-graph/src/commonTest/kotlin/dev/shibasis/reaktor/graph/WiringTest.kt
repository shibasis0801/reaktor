package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.node.awaitConnected
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WiringTest {
    @Test
    fun cancelledWaitsLeaveOtherConsumersWaitingAndAllListenersAreReleased() = runTest {
        val owner = object : PortCapabilityImpl() {
            val waiting = mutableSetOf<PortEventListener>()
            override fun addPortEventListener(listener: PortEventListener) {
                waiting.add(listener)
                super.addPortEventListener(listener)
            }
            override fun removePortEventListener(listener: PortEventListener) {
                waiting.remove(listener)
                super.removePortEventListener(listener)
            }
        }
        val consumer = owner.registerConsumer<String>("text")
        val provider = PortCapabilityImpl().registerProvider("text", "ready")
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { consumer.awaitConnected() }
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            consumer.awaitConnected()
            consumer()
        }
        assertEquals(2, owner.waiting.size)
        cancelled.cancelAndJoin()
        assertEquals(1, owner.waiting.size)
        connect(consumer, provider).getOrThrow()
        assertEquals("ready", active.await())
        assertEquals(0, owner.waiting.size)
        consumer.awaitConnected()
        assertEquals(0, owner.waiting.size)
        consumer.close()
        provider.close()
    }
}
