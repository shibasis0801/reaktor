package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.flow.graph.render.ReaktorGraphAccessibilityKind
import dev.shibasis.reaktor.flow.graph.render.reaktorGraphAccessibilityItems
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

    @Test
    fun graphAccessibilityInventoryNamesEveryVisibleNodePortAndConnection() {
        val dependencyAdapter = newDependencyAdapter()
        val graph = Graph(dependencyAdapter = dependencyAdapter, label = "AccessibleGraph")
        val home = RouteNode<Payload, RouteBinding<Payload>>(graph, "/home") { RouteBinding(Payload()) }
        val profile = RouteNode<Payload, RouteBinding<Payload>>(graph, "/profile") { RouteBinding(Payload()) }
        graph.attach(home)
        graph.attach(profile)
        home.edge(profile)

        val flow = buildReaktorFlowGraph(graph)
        val items = reaktorGraphAccessibilityItems(flow)
        val nodeCount = flow.nodes.count { !it.hidden && it.data is ReaktorGraphNodeData }
        val portCount = flow.nodes
            .filterNot { it.hidden }
            .mapNotNull { it.data as? ReaktorGraphNodeData }
            .sumOf { it.consumerPorts.size + it.providerPorts.size }
        val connectionCount = flow.edges.count { !it.hidden }

        assertEquals(nodeCount, items.count { it.kind == ReaktorGraphAccessibilityKind.Node })
        assertEquals(portCount, items.count { it.kind == ReaktorGraphAccessibilityKind.Port })
        assertEquals(connectionCount, items.count { it.kind == ReaktorGraphAccessibilityKind.Connection })
        assertTrue(items.filter { it.kind == ReaktorGraphAccessibilityKind.Node }.all { it.label.startsWith("Graph node: ") })
        assertTrue(items.filter { it.kind == ReaktorGraphAccessibilityKind.Port }.all { it.label.startsWith("Graph port: ") })
        assertTrue(
            items.filter { it.kind == ReaktorGraphAccessibilityKind.Connection }
                .all { it.label.startsWith("Graph connection: ") },
        )
        assertTrue(items.all { it.label.isNotBlank() })

        graph.close()
    }

    private fun newDependencyAdapter(): KoinDependencyAdapter {
        stopKoin()
        return KoinDependencyAdapter(startKoin {})
    }
}
