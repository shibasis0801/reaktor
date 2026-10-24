@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.shibasis.reaktor.service.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel as Buffer

private class Pipe : SocketTransport {
    val toServer = Buffer<String>(Buffer.UNLIMITED)
    val toClient = Buffer<String>(Buffer.UNLIMITED)
    override val incoming = toClient.consumeAsFlow()
    override val connection = MutableStateFlow(1)
    override suspend fun send(text: String) = toServer.send(text)
}

private fun socketed(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
    val workers = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    try {
        block(workers)
    } finally {
        workers.cancel()
    }
}

class SocketTest {
    @Test
    fun callsStreamsAndProblemsCrossOneSocket() = socketed { workers ->
        val pipe = Pipe()
        val session = SocketSession("ada", listOf(Served(GreeterServer())), emptyList(), workers) { pipe.toClient.send(it) }
        workers.launch { for (text in pipe.toServer) session.receive(text) }
        val api = Greeter() over SocketLink(pipe, workers)

        assertEquals("Hello, Ada! #x", api.greet(Greeting("Ada", excited = true, tags = listOf("x"))).text)
        assertEquals(Health(true), api.health())
        assertEquals(listOf(1, 2, 3), api.count(Count(3)).toList())
        val refused = assertFailsWith<Rejected> { api.sign(Sign("mallory", "hi")) }
        assertEquals(403, refused.status)
        assertEquals(GuestbookProblem.Banned("mallory"), refused.problemAs<GuestbookProblem>())
        advanceUntilIdle()
        assertEquals(0, session.open)
    }

    @Test
    fun channelsPublishAndDeliverAcrossTheSocket() = socketed { workers ->
        val news = topic<Chore>("test.news")
        val broker = Broker(news, workers)
        val local = mutableListOf<Int>()
        broker.subscribe("server") { local += it.id }
        val pipe = Pipe()
        val session = SocketSession("ada", emptyList(), listOf(broker), workers) { pipe.toClient.send(it) }
        workers.launch { for (text in pipe.toServer) session.receive(text) }
        val link = SocketLink(pipe, workers)

        val delivered = async { link.subscribe(news).take(2).toList() }
        advanceUntilIdle()
        assertEquals(listOf("server", "socket:ada:1"), broker.subscribers)

        link.publish(news, Chore(1))
        broker.publish(Chore(2))
        assertEquals(listOf(Chore(1), Chore(2)), delivered.await())
        advanceUntilIdle()
        assertEquals(listOf(1, 2), local)
        assertEquals(listOf("server"), broker.subscribers)
        assertEquals(0, session.open)

        val refused = assertFailsWith<Rejected> { link.publish(topic<Chore>("test.secret"), Chore(3)) }
        assertEquals(404, refused.status)
    }

    @Test
    fun aReconnectEndsExchangesWhoseAnswerCanNoLongerArrive() = socketed { workers ->
        val silent = Pipe()
        val api = Greeter() over SocketLink(silent, workers)

        val pending = async { runCatching { api.health() } }
        runCurrent()
        silent.connection.value = 2
        runCurrent()

        val failure = pending.await().exceptionOrNull()
        assertTrue(failure is OutcomeUnknown && "reconnected" in failure.message.orEmpty(), "$failure")
    }
}
