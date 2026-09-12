package dev.shibasis.reaktor.flow.graph.render

import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Position
import dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.ReaktorGraphSelection
import dev.shibasis.reaktor.flow.graph.ReaktorPortDirection
import dev.shibasis.reaktor.flow.graph.inspectPort
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.atDisplayDensity
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.portgraph.graph.connect
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ReaktorTypedNodeGeometryTest {
    @Test
    fun foldedOverviewRetainsExactPortsAndEdgesAtDisclosedFooterBoundary() {
        val koin = koinApplication { }
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin), label = "Folded service")
        try {
            val service = ManyPorts(graph)
            val client = ThreePorts(graph)
            graph.attach(service)
            graph.attach(client)
            connect(client.session, service.output3)
            val authored = DefaultReaktorGraphStyle.copy(
                typedNode = ReaktorGraphStyle.TypedNode(maxPortRows = 4),
                node = DefaultReaktorGraphStyle.node.copy(minWidthPx = 260.0, titleHeightPx = 30.0, footerHeightPx = 22.0),
                port = DefaultReaktorGraphStyle.port.copy(rowHeightPx = 22.0, insetPx = 14.0),
            )
            for (density in listOf(1f, 2f)) {
                val flow = buildReaktorFlowGraph(graph, authored.atDisplayDensity(density))
                val node = flow.nodes.single { flow.graphNodes[it.id] === service }
                val data = node.data as ReaktorGraphNodeData
                val height = requireNotNull(node.height)
                assertEquals(164.0, height / density, 0.00001)
                assertEquals(8, node.handles.size)
                assertEquals(8, data.consumerPorts.size + data.providerPorts.size)
                val rows = data.consumerPorts.map { HandleType.Target to it } +
                    data.providerPorts.map { HandleType.Source to it }
                rows.forEachIndexed { index, (direction, port) ->
                    val handle = node.handles.single { it.id == port.handleId && it.type == direction }
                    val center = requireNotNull(handle.offset) * height / density
                    assertEquals(if (index < 4) 65.0 + index * 22.0 else 153.0, center, 0.00001)
                    assertNotNull(flow.inspectPort(ReaktorGraphSelection.Port(node.id, port.handleId,
                        if (direction == HandleType.Target) ReaktorPortDirection.Consumer else ReaktorPortDirection.Provider)))
                }
                val edge = flow.edges.single { it.source == node.id && it.sourceHandle == "output3" }
                assertEquals(flow.flowIdsByNode.getValue(client), edge.target)
                assertEquals("session", edge.targetHandle)
            }
        } finally {
            graph.close()
            koin.close()
        }
    }

    @Test
    fun actualThreePortNodeUsesAuthoredHeightAndDirectedRowCentersAtBothDensities() {
        val koin = koinApplication { }
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin), label = "Typed card")
        try {
            val subject = ThreePorts(graph)
            graph.attach(subject)
            // FsQx4/N54ZXW anatomy: family24 + title30 + three22px rows + footer22.
            // Deliberately retain the default compact-card vertical padding: a typed card has no
            // such band, and enabling the typed renderer must not move its directed handles.
            val authored = DefaultReaktorGraphStyle.copy(
                typedNode = ReaktorGraphStyle.TypedNode(familyHeightPx = 24.0),
                node = DefaultReaktorGraphStyle.node.copy(
                    minWidthPx = 260.0, titleHeightPx = 30.0, footerHeightPx = 22.0,
                ),
                port = DefaultReaktorGraphStyle.port.copy(rowHeightPx = 22.0, dotSizePx = 8.0, insetPx = 14.0),
            )
            for (density in listOf(1f, 2f)) {
                val flow = buildReaktorFlowGraph(graph, authored.atDisplayDensity(density))
                val node = flow.nodes.single { flow.graphNodes[it.id] === subject }
                val data = node.data as ReaktorGraphNodeData
                val height = requireNotNull(node.height)
                assertEquals(260.0, requireNotNull(node.width) / density, 0.00001)
                assertEquals(142.0, height / density, 0.00001)
                assertEquals(setOf("session", "user"), data.consumerPorts.map { it.handleId }.toSet())
                assertEquals(listOf("state"), data.providerPorts.map { it.handleId })
                assertEquals(3, node.handles.size)

                val rows = data.consumerPorts.map { HandleType.Target to it } +
                    data.providerPorts.map { HandleType.Source to it }
                val authoredRowCenters = listOf(65.0, 87.0, 109.0)
                rows.forEachIndexed { rowIndex, (direction, port) ->
                    val handle = node.handles.single { it.id == port.handleId && it.type == direction }
                    assertEquals(if (direction == HandleType.Target) Position.Left else Position.Right, handle.position)
                    assertEquals(authoredRowCenters[rowIndex], requireNotNull(handle.offset) * height / density,
                        0.00001, "${port.handleId} at density $density must meet its painted row center")
                    assertEquals(14.0, requireNotNull(handle.inset) / density, 0.00001)
                    val localX = if (direction == HandleType.Target) requireNotNull(handle.inset)
                        else requireNotNull(node.width) - requireNotNull(handle.inset)
                    assertEquals(if (direction == HandleType.Target) 14.0 else 246.0,
                        localX / density, 0.00001)
                }
                assertTrue(data.consumerPorts.all { !it.connected }, "Inspection must not fabricate bindings")
            }
        } finally {
            graph.close()
            koin.close()
        }
    }

    private class ThreePorts(graph: Graph) : BasicNode(graph) {
        val session by consumes<String>(name = "session")
        val user by consumes<String>(name = "user")
        val state by provides("ready", name = "state")
    }

    private class ManyPorts(graph: Graph) : BasicNode(graph) {
        val input0 by consumes<String>(name = "input0")
        val input1 by consumes<String>(name = "input1")
        val input2 by consumes<String>(name = "input2")
        val input3 by consumes<String>(name = "input3")
        val output0 by provides("ready", name = "output0")
        val output1 by provides("ready", name = "output1")
        val output2 by provides("ready", name = "output2")
        val output3 by provides("ready", name = "output3")
    }
}
