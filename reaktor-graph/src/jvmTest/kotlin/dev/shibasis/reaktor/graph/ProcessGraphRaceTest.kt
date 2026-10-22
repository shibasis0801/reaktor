package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.ProcessGraph
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two entry points asking for the process graph at the same moment.
 *
 * On Android that is ordinary, not contrived: a media-button press starts the playback service
 * while the launcher is starting the Activity, and both reach for the session on their own threads.
 * A graph built twice is two players, two database observers and two sets of ports, and whichever
 * one the Activity got is not the one the lock screen is driving.
 */
class ProcessGraphRaceTest {

    @Test
    fun entryPointsArrivingTogetherShareOneGraph() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            repeat(ROUNDS) {
                val builds = AtomicInteger()
                val holder = ProcessGraph {
                    builds.incrementAndGet()
                    Graph(dependencyAdapter = ScopedTestAdapter(), label = "session")
                }
                val gate = CyclicBarrier(THREADS)
                val seen = (1..THREADS).map {
                    pool.submit<Graph> { gate.await(); holder.get() }
                }.map { it.get(5, TimeUnit.SECONDS) }

                assertEquals(1, builds.get(), "round $it built the graph more than once")
                assertEquals(1, seen.toSet().size, "round $it handed out different graphs")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 500
    }
}
