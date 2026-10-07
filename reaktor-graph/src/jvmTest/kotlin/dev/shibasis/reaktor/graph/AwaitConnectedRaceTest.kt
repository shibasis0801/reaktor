package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.node.awaitConnected
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A node waits for its port while another thread wires the graph.
 *
 * That is not a contrived arrangement, it is every launch: nodes start their work from `init` on a
 * background dispatcher and `autoWire` runs on whichever thread built the graph. The two meet in
 * `awaitConnected`, where both can notice the same connection — and a continuation that is resumed
 * twice throws, inside the node's coroutine, under a supervisor that reports nothing. The node then
 * never starts the work it was waiting to do.
 *
 * Found once in six runs of an application's suite. Here the two sides are released together, many
 * times, so that the window is hit on purpose.
 */
class AwaitConnectedRaceTest {

    @Test
    fun waitingAndWiringAtTheSameMomentWakesTheWaiterOnceAndOnlyOnce() {
        val threads = Executors.newFixedThreadPool(2)
        val pool = threads.asCoroutineDispatcher()
        val failures = mutableListOf<Throwable>()

        try {
            runBlocking {
                repeat(ROUNDS) {
                    val consumer = PortCapabilityImpl().registerConsumer<String>("message")
                    val provider = PortCapabilityImpl().registerProvider("message", "hello")
                    val together = CyclicBarrier(2)

                    val waiting = async(pool) {
                        together.await()
                        runCatching { consumer.awaitConnected() }
                    }
                    val wiring = async(pool) {
                        together.await()
                        runCatching { connect(consumer, provider).getOrThrow() }
                    }

                    // A waiter that is never woken is the other way this can go wrong.
                    withTimeout(10_000) { awaitAll(waiting, wiring) }
                        .mapNotNull { it.exceptionOrNull() }
                        .let(failures::addAll)
                }
            }
        } finally {
            threads.shutdownNow()
        }

        assertEquals(emptyList(), failures.map { "${it::class.simpleName}: ${it.message}" }.distinct())
    }

    private companion object {
        const val ROUNDS = 30_000
    }
}
