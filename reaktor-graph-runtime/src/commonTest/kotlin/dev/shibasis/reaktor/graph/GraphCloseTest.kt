package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.wire
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphCloseTest {
    class Clock(graph: Graph, private val stopped: MutableStateFlow<Boolean>) : BasicNode(graph) {
        val clock by provides<Clock>(this)

        override fun close() {
            super.close()
            stopped.value = true
        }
    }

    class Reader(graph: Graph, stopped: StateFlow<Boolean>, failures: MutableList<Throwable>) : BasicNode(graph) {
        val clock by consumes<Clock>()

        init {
            coroutineScope.launch {
                stopped.first { it }
                runCatching { clock() }.onFailure { failures += it }
            }
        }
    }

    @Test fun closingAGraphStopsTheWorkOfEveryNodeBeforeAnyOfItsPortsClose() {
        Reaktor.web()
        val failures = mutableListOf<Throwable>()
        repeat(32) {
            val stopped = MutableStateFlow(false)
            val application = Graph(dispatcher = Dispatchers.Unconfined, label = "application")
            application.Node { Clock(it, stopped) }
            application.Node { Reader(it, stopped, failures) }
            application.wire()

            application.close()

            assertTrue(stopped.value)
        }
        assertEquals(emptyList(), failures.map { it.message })
    }
}
