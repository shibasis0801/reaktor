@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.SpanBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class Chore(val id: Int, val owner: String = "ada")

private fun brokered(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
    val workers = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    try {
        block(workers)
    } finally {
        workers.cancel()
    }
}

class BrokerTest {
    @Test
    fun queuesHandEachMessageToOneSubscriber() = brokered { workers ->
        val broker = Broker(queue<Chore>("test.chores"), workers)
        val done = mutableListOf<Pair<String, Int>>()
        broker.subscribe("first") { done += "first" to it.id }
        broker.subscribe("second") { done += "second" to it.id }

        (1..6).forEach { broker.publish(Chore(it)) }
        advanceUntilIdle()

        assertEquals((1..6).toList(), done.map { it.second }.sorted())
    }

    @Test
    fun topicsHandEveryMessageToEverySubscriber() = brokered { workers ->
        val broker = Broker(topic<Chore>("test.news"), workers)
        val done = mutableListOf<Pair<String, Int>>()
        broker.subscribe("first") { done += "first" to it.id }
        broker.subscribe("second") { done += "second" to it.id }

        (1..3).forEach { broker.publish(Chore(it)) }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), done.filter { it.first == "first" }.map { it.second })
        assertEquals(listOf(1, 2, 3), done.filter { it.first == "second" }.map { it.second })
    }

    @Test
    fun failedDeliveriesRetryThenGoToTheDeadLetters() = brokered { workers ->
        val broker = Broker(queue<Chore>("test.chores", maxAttempts = 3), workers)
        val attempts = mutableMapOf<Int, Int>()
        val done = mutableListOf<Int>()
        broker.subscribe("worker") { chore ->
            val attempt = (attempts[chore.id] ?: 0) + 1
            attempts[chore.id] = attempt
            if (chore.id == 3 || (chore.id == 2 && attempt == 1)) error("chore ${chore.id} failed")
            if (chore.id == 4) reject(422, "chore 4 is not ours")
            done += chore.id
        }

        (1..5).forEach { broker.publish(Chore(it)) }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 5), done)
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 1, 5 to 1), attempts)
        assertEquals(listOf(3 to 3, 4 to 1), broker.deadLetters.map { it.envelope.message.id to it.envelope.attempt })
        assertIs<Rejected>(broker.deadLetters.last().failure)
    }

    @Test
    fun perKeyOrderingHoldsAcrossConcurrentWorkers() = brokered { workers ->
        val broker = Broker(queue<Chore>("test.chores", ordering = Ordering.PerKey { it.owner }), workers)
        val done = mutableListOf<Chore>()
        broker.subscribe("worker", concurrency = 3) { chore ->
            delay(if (chore.owner == "ada") 30.milliseconds else 5.milliseconds)
            done += chore
        }

        (1..12).forEach { broker.publish(Chore(it, listOf("ada", "bob", "cy")[it % 3])) }
        advanceUntilIdle()

        assertEquals(12, done.size)
        done.groupBy { it.owner }.values.forEach { chores -> assertEquals(chores.map { it.id }.sorted(), chores.map { it.id }) }
    }

    @Test
    fun platformBatchesSettleEachMessage() = brokered { workers ->
        val chores = queue<Chore>("test.chores", maxAttempts = 2)
        val broker = Broker(chores, workers)
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        assertIs<Settlement.Retry>(broker.receive(Envelope(Chore(1), Metadata(), null)))

        val seen = mutableListOf<String?>()
        broker.subscribe("worker") { chore ->
            seen += exchange().call.trace.traceId
            if (chore.id == 2) error("broken")
        }
        val sealed = chores.seal(Chore(1), Metadata(mapOf(Metadata.Traceparent to traceparent)))
        assertEquals(Settlement.Ack, broker.receive(chores.unseal(sealed)))
        assertEquals("0af7651916cd43dd8448eb211c80319c", seen.single())

        assertIs<Settlement.Retry>(broker.receive(chores.unseal(chores.seal(Chore(2), Metadata()), attempt = 1)))
        assertEquals(Settlement.Ack, broker.receive(chores.unseal(chores.seal(Chore(2), Metadata()), attempt = 2)))
        assertEquals(listOf(2), broker.deadLetters.map { it.envelope.message.id })

        val bare = chores.unseal(buildJsonObject { put("id", 7); put("owner", "bob") }, attempt = 3)
        assertEquals(Chore(7, "bob"), bare.message)
        assertEquals(3, bare.attempt)
    }

    @Test
    fun publishedMessagesStayInTheSenderTrace() = brokered { workers ->
        val spans = SpanBuffer()
        val broker = Broker(queue<Chore>("test.chores"), workers)
        broker.interceptors.add(Spans("test", spans))
        broker.subscribe("worker") {}

        broker.publish(Chore(1))
        advanceUntilIdle()

        val (sent, delivered) = spans.snapshot().sortedBy { it.phase }
        assertEquals(ServiceExecutionPhase.CLIENT, sent.phase)
        assertEquals("test.chores" to "publish", sent.contract to sent.operation)
        assertEquals("test.chores" to "worker", delivered.contract to delivered.operation)
        assertEquals(sent.traceId, delivered.traceId)
        assertEquals(sent.spanId, delivered.parentId)
        assertTrue(delivered.spanId != sent.spanId)
    }
}
