package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.awaitConnected
import dev.shibasis.reaktor.graph.core.node.launchWhenWired
import dev.shibasis.reaktor.graph.core.node.provide
import dev.shibasis.reaktor.graph.core.wire
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WiringTest {
    class Users(graph: Graph) : BasicNode(graph) {
        val users by provides<Users>(this)
        val greeting by consumes<String>()
    }

    class Session(graph: Graph) : BasicNode(graph) {
        val users by consumes<Users>()
        var early: Result<String>? = null
        var greeting: String? = null

        init {
            coroutineScope.launch { users.awaitConnected(); early = runCatching { users().greeting() } }
            launchWhenWired(users) { greeting = users().greeting() }
        }
    }

    @Test
    fun workWaitsForTheWholeTreeNotJustItsOwnPorts() {
        Reaktor.web()
        val app = Graph(dispatcher = Dispatchers.Unconfined, label = "app")
        val session = app.Node(::Session)
        app.Graph { Graph(it, label = "identity") }.Node(::Users)
        app.Graph { Graph(it, label = "config") }.provide("hello", "greeting")
        app.wire()
        assertIs<IllegalStateException>(session.early?.exceptionOrNull())
        assertEquals("hello", session.greeting)
        app.close()
    }

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
