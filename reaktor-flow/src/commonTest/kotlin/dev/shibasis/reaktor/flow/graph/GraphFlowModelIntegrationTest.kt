package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.navigation.Payload
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphFlowModelIntegrationTest {
    @Test
    fun routeNavigationProducesExplicitHandleBoundEdges() {
        val dependencyAdapter = newDependencyAdapter()
        val graph = Graph(dependencyAdapter = dependencyAdapter, label = "TestGraph")
        val home = RouteNode<Payload, RouteBinding<Payload>>(graph, "/home") { RouteBinding(Payload()) }
        val profile = RouteNode<Payload, RouteBinding<Payload>>(graph, "/profile") { RouteBinding(Payload()) }

        graph.attach(home)
        graph.attach(profile)
        home.edge(profile)

        val flow = buildReaktorFlowGraph(graph)
        val navigationEdge = flow.edges.firstOrNull {
            (it.data as? ReaktorGraphEdgeData)?.kind == ReaktorEdgeKind.Navigation
        }

        assertNotNull(navigationEdge)
        assertEquals("__nav__", navigationEdge.sourceHandle)
        assertEquals("navBinding", navigationEdge.targetHandle)

        val homeNode = flow.nodes.first { it.id == flow.flowIdsByNode.getValue(home) }
        val profileNode = flow.nodes.first { it.id == flow.flowIdsByNode.getValue(profile) }

        assertTrue(homeNode.handles.any { it.id == "__nav__" })
        assertTrue(profileNode.handles.any { it.id == "navBinding" })
    }

    @Test
    fun graphBuildsRegionsAndColoredPortMetadata() {
        val dependencyAdapter = newDependencyAdapter()
        val graph = Graph(dependencyAdapter = dependencyAdapter, label = "TestGraph")
        val home = RouteNode<Payload, RouteBinding<Payload>>(graph, "/home") { RouteBinding(Payload()) }

        graph.attach(home)

        val flow = buildReaktorFlowGraph(graph)

        assertTrue(flow.regions.isNotEmpty(), "expected graph regions for overlay rendering")
        val graphPortData = flow.nodes
            .mapNotNull { it.data as? ReaktorGraphNodeData }
            .flatMap { it.providerPorts + it.consumerPorts }
        assertTrue(graphPortData.isNotEmpty(), "expected graph ports in the flow model")
        assertTrue(graphPortData.any { it.color.value != 0UL }, "expected ports to carry accent colors")
    }

    private fun newDependencyAdapter(): KoinDependencyAdapter {
        stopKoin()
        return KoinDependencyAdapter(startKoin {})
    }
}
