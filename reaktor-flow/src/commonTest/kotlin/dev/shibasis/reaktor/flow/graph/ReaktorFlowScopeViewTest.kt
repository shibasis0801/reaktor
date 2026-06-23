package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.navigation.Payload
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for the hierarchical scope-local projection
 * (see docs/reaktor-graph-hierarchical-ui.md §6, §11).
 *
 * Fixture: root graph "root" owns a container with two child graphs — "root/0" (alpha) and
 * "root/1" (beta), each with one route node. Everything lives as members of this single class so
 * the JVM test runner does not pick up helper classes as bogus test classes.
 */
class ReaktorFlowScopeViewTest {

    private lateinit var root: Graph
    private lateinit var alpha: Graph
    private lateinit var beta: Graph
    private lateinit var alphaRoute: RouteNode<Payload, RouteBinding<Payload>>
    private lateinit var betaRoute: RouteNode<Payload, RouteBinding<Payload>>

    @BeforeTest
    fun setup() {
        val adapter = newAdapter()
        root = Graph(dependencyAdapter = adapter, label = "root")
        alpha = Graph(parentGraph = root, dependencyAdapter = adapter, label = "alpha")
        beta = Graph(parentGraph = root, dependencyAdapter = adapter, label = "beta")
        alphaRoute = RouteNode(alpha, "/alpha") { RouteBinding(Payload()) }
        betaRoute = RouteNode(beta, "/beta") { RouteBinding(Payload()) }
        alpha.attach(alphaRoute)
        beta.attach(betaRoute)
        root.attach(ContainerNode(root, "/panes", arrayListOf(alpha, beta)))
    }

    @Test
    fun rootScopeShowsChildSubgraphsAsCollapsedBoundariesNotTheirInternals() {
        val flow = buildReaktorFlowGraph(root, scopeView = ReaktorFlowScopeView())

        // Each direct child graph renders as a single boundary summary node at the next level.
        assertTrue(flow.nodes.any { it.id == "root/0" }, "expected collapsed summary node for alpha")
        assertTrue(flow.nodes.any { it.id == "root/1" }, "expected collapsed summary node for beta")
        assertEquals(alpha, flow.graphs["root/0"])
        assertEquals(beta, flow.graphs["root/1"])

        // The children's internals are NOT laid out at the root level.
        assertNull(flow.flowIdsByNode[alphaRoute], "alpha internals should be hidden while collapsed")
        assertNull(flow.flowIdsByNode[betaRoute], "beta internals should be hidden while collapsed")

        // A containment edge folds from the container to each collapsed boundary.
        assertTrue(
            flow.edges.any { edge ->
                edge.target == "root/0" &&
                    (edge.data as? ReaktorGraphEdgeData)?.kind == ReaktorEdgeKind.Containment
            },
            "expected a containment edge into the collapsed alpha boundary",
        )

        // Boundary regions exist for the root and both child scopes.
        val regionIds = flow.regions.map { it.id }.toSet()
        assertTrue(regionIds.containsAll(setOf("root", "root/0", "root/1")))
    }

    @Test
    fun expandingOneSubgraphRevealsItsChildrenWhileSiblingsStayCollapsed() {
        val flow = buildReaktorFlowGraph(
            root,
            scopeView = ReaktorFlowScopeView(expandedScopeIds = setOf("root/0")),
        )

        // alpha is expanded: its route node is laid out.
        assertNotNull(flow.flowIdsByNode[alphaRoute], "expanded alpha should lay out its nodes")
        // beta stays collapsed: still a single boundary, internals hidden.
        assertTrue(flow.nodes.any { it.id == "root/1" }, "beta should remain a collapsed boundary")
        assertNull(flow.flowIdsByNode[betaRoute], "beta internals should remain hidden")
    }

    @Test
    fun nullScopeViewKeepsLegacyFullyRecursiveProjection() {
        val flow = buildReaktorFlowGraph(root, scopeView = null)

        // Every nested graph is laid out at once; no synthetic boundary summary nodes.
        assertNotNull(flow.flowIdsByNode[alphaRoute])
        assertNotNull(flow.flowIdsByNode[betaRoute])
        assertFalse(flow.nodes.any { it.id == "root/0" }, "legacy projection should not emit summary nodes")
    }

    @Test
    fun scopeViewExpandIncludesAncestorsAndCollapsePrunesDescendants() {
        val expanded = ReaktorFlowScopeView().expand("root/0/2")
        assertEquals(setOf("root", "root/0", "root/0/2"), expanded.expandedScopeIds)
        assertTrue(expanded.isExpanded("root/0"))

        val collapsed = expanded.collapse("root/0")
        assertFalse(collapsed.isExpanded("root/0"))
        assertFalse(collapsed.isExpanded("root/0/2"))
        assertTrue(collapsed.isExpanded("root"))

        assertEquals(0, ReaktorFlowScopeView.depthOf("root"))
        assertEquals(2, ReaktorFlowScopeView.depthOf("root/0/2"))
        assertEquals("root/3", ReaktorFlowScopeView.childScopeId("root", 3))
    }

    private fun newAdapter(): KoinDependencyAdapter {
        stopKoin()
        return KoinDependencyAdapter(startKoin {})
    }
}
