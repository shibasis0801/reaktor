@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.contract.Spans
import dev.shibasis.reaktor.service.contract.keyed
import dev.shibasis.reaktor.service.contract.queue
import dev.shibasis.reaktor.service.contract.topic
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
data class Ping(val n: Int)

private val Buried = queue<Ping>("test.pings.dead")
private val Pings = queue<Ping>("test.pings", maxAttempts = 2, deadLetter = Buried)

class Pinger(graph: Graph) : BasicNode(graph) {
    val pings by publishes(Pings)
}

class Ponger(graph: Graph, seen: MutableList<Int>) : BasicNode(graph) {
    val inbox by subscribes(Pings) { ping ->
        check(ping.n >= 0) { "negative ping" }
        seen += ping.n
    }
}

class Morgue(graph: Graph, buried: MutableList<Int>) : BasicNode(graph) {
    val dead by subscribes(Buried) { buried += it.n }
}

@Serializable
data class Sighting(val bird: String, val second: Long)

@Serializable
data class Confirmed(val bird: String, val first: Long, val second: Long)

private val North = topic<Sighting>("test.north")
private val South = topic<Sighting>("test.south")
private val Both = topic<Confirmed>("test.confirmed")

class Birder(graph: Graph, confirmed: MutableList<Confirmed>) : BasicNode(graph) {
    val north by publishes(North)
    val south by publishes(South)
    val log by subscribes(Both) { confirmed += it }
}

class ChannelNodeTest {
    @Test
    fun channelsConnectPublishersAndSubscribersThroughTheGraph() = runTest {
        Reaktor.web()
        val spans = SpanBuffer()
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), label = "pings")
        graph.intercept(Spans("pings", spans))
        val channel = graph.channel(Pings)
        graph.channel(Buried)
        val seen = mutableListOf<Int>()
        val buried = mutableListOf<Int>()
        val pinger = graph.Node { Pinger(it) }
        val ponger = graph.Node { Ponger(it, seen) }
        graph.Node { Morgue(it, buried) }
        graph.autoWire()

        assertTrue(ponger.inbox.active)
        pinger.pings(Ping(1))
        pinger.pings(Ping(-1))
        pinger.pings(Ping(2))
        advanceUntilIdle()

        assertEquals(listOf(1, 2), seen)
        assertEquals(listOf(-1), buried)
        assertEquals(listOf("inbox"), channel.broker.subscribers)

        val shape = graph.shape()
        val node = shape.nodes.single { it.id == channel.id.toString() }
        assertEquals("queue", node.channel?.family)
        assertEquals("test.pings.dead", node.channel?.deadLetter)
        assertEquals("Graph", node.interceptors.single().scope)
        val published = shape.wires.single { it.consumer == pinger.id.toString() }
        assertEquals("publish:test.pings", published.type)
        assertTrue(shape.wires.any { it.consumer == ponger.id.toString() && it.type == "subscribe:test.pings" })
        assertTrue(shape.wires.any { it.consumer == channel.id.toString() && it.type == "publish:test.pings.dead" })

        val recorded = spans.snapshot().filter { it.contract == "test.pings" }
        val sent = recorded.first { it.phase == ServiceExecutionPhase.CLIENT }
        val delivered = recorded.single { it.phase == ServiceExecutionPhase.SERVER && it.parentId == sent.spanId }
        assertTrue(published.definitionId.isNotEmpty())
        assertEquals(published.definitionId, sent.edge)
        assertEquals("inbox", delivered.operation)
        assertEquals(node.definitionId, delivered.node)
        assertEquals(node.ports.single { it.provides && it.type == "subscribe:test.pings" }.definitionId, delivered.port)
        assertEquals(sent.traceId, delivered.traceId)

        ponger.close()
        assertFalse(ponger.inbox.active)
        assertTrue(channel.broker.subscribers.isEmpty())
        graph.close()
    }

    @Test
    fun joinsPairSightingsFromTwoChannelsInsideTheWindow() = runTest {
        Reaktor.web()
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), label = "birds")
        listOf(North, South, Both).forEach { graph.channel(it) }
        val stamp = keyed<Sighting, String>({ Instant.fromEpochSeconds(it.second) }) { it.bird }
        val join = graph.join(North, South, Both, 10.seconds, stamp, stamp, lateness = 120.seconds) { north, south -> Confirmed(north.bird, north.second, south.second) }
        val confirmed = mutableListOf<Confirmed>()
        val birder = graph.Node { Birder(it, confirmed) }
        graph.autoWire()

        birder.north(Sighting("heron", 100))
        birder.south(Sighting("heron", 104))
        birder.south(Sighting("kite", 104))
        birder.north(Sighting("kite", 200))
        advanceUntilIdle()

        assertEquals(listOf(Confirmed("heron", 100, 104)), confirmed)
        val wires = graph.shape().wires.filter { it.consumer == join.id.toString() }.map { it.type }.sorted()
        assertEquals(listOf("publish:test.confirmed", "subscribe:test.north", "subscribe:test.south"), wires)
        graph.close()
    }
}
