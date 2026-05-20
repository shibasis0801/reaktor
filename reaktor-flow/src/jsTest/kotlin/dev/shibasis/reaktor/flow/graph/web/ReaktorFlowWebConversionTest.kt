package dev.shibasis.reaktor.flow.graph.web

import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaktorFlowWebConversionTest {

    private fun sampleFlow(): ReaktorFlowGraph {
        val nodeData = ReaktorGraphNodeData(
            nodeId = "n1",
            title = "AuthService",
            subtitle = "auth",
            graphLabel = "root",
            isRootNode = false,
            kind = ReaktorNodeKind.Service,
            providerPorts = listOf(
                ReaktorPortData("out-session", "session", "provider", Color(0xFF55D46E), true),
            ),
            consumerPorts = listOf(
                ReaktorPortData("in-login", "login", "consumer", Color(0xFF55D46E), true),
            ),
            providerCount = 1,
            consumerCount = 1,
            hiddenProviderCount = 0,
            hiddenConsumerCount = 0,
        )

        val edgeData = ReaktorGraphEdgeData(
            kind = ReaktorEdgeKind.Data,
            label = "authService",
        )

        return ReaktorFlowGraph(
            nodes = listOf(
                Node(id = "n1", position = XYPosition(100.0, 200.0), data = nodeData, type = "reaktorNode"),
                Node(id = "n2", position = XYPosition(300.0, 200.0), data = "plain"),
            ),
            edges = listOf(
                Edge(id = "e1", source = "n1", target = "n2", data = edgeData),
            ),
            regions = listOf(
                ReaktorGraphRegion("r1", "Services", 50.0, 150.0, 400.0, 200.0, Color(0xFFCC8844), 0),
            ),
            graphNodes = emptyMap(),
            flowIdsByNode = emptyMap(),
            graphIdsByNode = emptyMap(),
            graphs = emptyMap(),
        )
    }

    @Test
    fun conversionPreservesNodeAndEdgeCounts() {
        val js = sampleFlow().toReactFlowData()
        assertEquals(2, js.nodes.size)
        assertEquals(1, js.edges.size)
        assertEquals(1, js.regions.size)
    }

    @Test
    fun regionColorIsHex() {
        val js = sampleFlow().toReactFlowData()
        val region = js.regions[0]
        assertEquals("Services", region.label)
        assertEquals("#cc8844", region.color)
    }

    @Test
    fun nodeDataKindIsString() {
        val js = sampleFlow().toReactFlowData()
        val data = js.nodes[0].asDynamic().data
        assertEquals("Service", data.kind as String)
        assertEquals("AuthService", data.title as String)
    }

    @Test
    fun edgeDataKindIsString() {
        val js = sampleFlow().toReactFlowData()
        val data = js.edges[0].asDynamic().data
        assertEquals("Data", data.kind as String)
        assertEquals("authService", data.label as String)
    }

    @Test
    fun portColorIsHex() {
        val js = sampleFlow().toReactFlowData()
        val data = js.nodes[0].asDynamic().data
        val port = (data.providerPorts as Array<dynamic>)[0]
        assertEquals("#55d46e", port.color as String)
    }
}
