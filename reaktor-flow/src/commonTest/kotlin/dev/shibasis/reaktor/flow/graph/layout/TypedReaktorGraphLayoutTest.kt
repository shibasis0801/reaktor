package dev.shibasis.reaktor.flow.graph.layout

import dev.shibasis.reaktor.flow.graph.adapter.buildReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowScopeView
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.atDisplayDensity
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.consumes
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedReaktorGraphLayoutTest {
    private val authored = DefaultReaktorGraphStyle.copy(
        typedNode = ReaktorGraphStyle.TypedNode(),
        node = DefaultReaktorGraphStyle.node.copy(minWidthPx = 260.0, titleHeightPx = 30.0,
            footerHeightPx = 22.0, verticalPaddingPx = 0.0),
        port = DefaultReaktorGraphStyle.port.copy(rowHeightPx = 22.0),
        layout = DefaultReaktorGraphStyle.layout.copy(compactColumnGapPx = 40.0, rowGapPx = 34.0),
        region = DefaultReaktorGraphStyle.region.copy(contentPaddingXPx = 18.0),
    )

    @Test
    fun realTopologyWrapsRootAndChildrenWithoutLosingIdentityOrRelations() = fixture { root, child, route, screen ->
        val scope = ReaktorFlowScopeView().expand("root/0")
        val actual = buildReaktorFlowGraph(root, authored, scope)
        val compact = buildReaktorFlowGraph(root, authored.copy(typedNode = null), scope)
        assertEquals(compact.nodes.map { it.id }.toSet(), actual.nodes.map { it.id }.toSet())
        assertEquals(compact.regions.map { it.id }.toSet(), actual.regions.map { it.id }.toSet())
        assertEquals(compact.edges.map { listOf(it.source, it.sourceHandle, it.target, it.targetHandle) }.toSet(),
            actual.edges.map { listOf(it.source, it.sourceHandle, it.target, it.targetHandle) }.toSet())
        assertEquals(actual.nodes.size, actual.nodes.map { it.id }.distinct().size)
        val direct = actual.nodes.filter { actual.graphNodes[it.id]?.graph === root }
        val nested = actual.nodes.filter { actual.graphNodes[it.id]?.graph === child }
        assertEquals(6, direct.map { it.position.x }.distinct().size)
        assertEquals(4, nested.map { it.position.x }.distinct().size)
        val summaries = actual.nodes.filter { (it.data as? ReaktorGraphNodeData)?.isScopeSummary == true }
        assertEquals(7, summaries.size)
        assertEquals(2, summaries.map { it.position.x }.distinct().size)
        val expandedRegion = actual.regions.single { it.id == "root/0" }
        assertTrue(summaries.all { it.position.x > expandedRegion.x + expandedRegion.width })
        assertTrue(actual.regions.single { it.id == "root" }.width < 2000.0,
            "One expanded feature plus seven folded siblings must not become an unbounded horizontal strip")

        val routeCard = actual.nodes.single { actual.graphNodes[it.id] === route }
        val screenCard = actual.nodes.single { actual.graphNodes[it.id] === screen }
        assertEquals(routeCard.position.y, screenCard.position.y)
        assertEquals(routeCard.position.x + requireNotNull(routeCard.width) + authored.layout.compactColumnGapPx,
            screenCard.position.x)
        assertNoNodeOverlap(actual)
    }

    @Test
    fun boundedPackingUsesScaledGraphSpaceRatherThanUnscaledPixelConstants() = fixture { root, _, _, _ ->
        val scope = ReaktorFlowScopeView().expand("root/0")
        val baseline = buildReaktorFlowGraph(root, authored.atDisplayDensity(1f), scope)
        val doubled = buildReaktorFlowGraph(root, authored.atDisplayDensity(2f), scope)
        assertEquals(baseline.nodes.map { it.id }, doubled.nodes.map { it.id })
        baseline.nodes.zip(doubled.nodes).forEach { (expected, actual) ->
            assertEquals(expected.position.x, actual.position.x / 2, 0.00001)
            assertEquals(expected.position.y, actual.position.y / 2, 0.00001)
            assertEquals(requireNotNull(expected.width), requireNotNull(actual.width) / 2, 0.00001)
            assertEquals(requireNotNull(expected.height), requireNotNull(actual.height) / 2, 0.00001)
        }
        baseline.regions.zip(doubled.regions).forEach { (expected, actual) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.width, actual.width / 2, 0.00001)
            assertEquals(expected.height, actual.height / 2, 0.00001)
        }
        assertNoNodeOverlap(doubled)
    }

    @Test
    fun viewportOrientationPlacesChildrenAlongTheLongAxisWithoutChangingIdentity() = fixture { root, child, route, screen ->
        val scope = ReaktorFlowScopeView().expand("root/0")
        val legacy = buildReaktorFlowGraph(root, authored, scope)
        listOf(900.0 to 600.0, 900.0 to 1600.0, 3200.0 to 1200.0).forEach { (width, height) ->
            val style = authored.copy(layout = authored.layout.copy(targetContentWidthPx = width, targetContentHeightPx = height))
            val actual = buildReaktorFlowGraph(root, style, scope)
            assertEquals(legacy.nodes.map { it.id }.toSet(), actual.nodes.map { it.id }.toSet())
            assertEquals(legacy.allScopeIds, actual.allScopeIds)
            assertEquals(legacy.edges.map { listOf(it.source, it.sourceHandle, it.target, it.targetHandle) }.toSet(),
                actual.edges.map { listOf(it.source, it.sourceHandle, it.target, it.targetHandle) }.toSet())
            val direct = actual.nodes.filter { actual.graphNodes[it.id]?.graph === root }
            val expanded = actual.regions.single { actual.graphs[it.id] === child }
            if (width >= height) {
                assertTrue(expanded.x > direct.maxOf { it.position.x + requireNotNull(it.width) })
            } else {
                assertTrue(expanded.y > direct.maxOf { it.position.y + requireNotNull(it.height) })
            }
            val routeCard = actual.nodes.single { actual.graphNodes[it.id] === route }
            val screenCard = actual.nodes.single { actual.graphNodes[it.id] === screen }
            assertEquals(routeCard.position.y, screenCard.position.y)
            assertEquals(routeCard.position.x + requireNotNull(routeCard.width) + authored.layout.compactColumnGapPx,
                screenCard.position.x)
            assertNoNodeOverlap(actual)
            val children = actual.regions.filter { it.depth == 1 }
            children.forEachIndexed { index, a -> children.drop(index + 1).forEach { b ->
                assertFalse(a.x < b.x + b.width && b.x < a.x + a.width &&
                    a.y < b.y + b.height && b.y < a.y + a.height, "Sibling regions overlap: ${a.id}, ${b.id}")
            } }
            val enclosing = actual.regions.single { it.id == "root" }
            assertTrue(children.all { it.x >= enclosing.x && it.y >= enclosing.y &&
                it.x + it.width <= enclosing.x + enclosing.width && it.y + it.height <= enclosing.y + enclosing.height })
        }
    }

    @Test
    fun viewportPackingScalesWithDisplayDensity() = fixture { root, _, _, _ ->
        listOf(1600.0 to 900.0, 900.0 to 1600.0).forEach { (width, height) ->
            val style = authored.copy(layout = authored.layout.copy(targetContentWidthPx = width, targetContentHeightPx = height))
            val scope = ReaktorFlowScopeView().expand("root/0")
            val baseline = buildReaktorFlowGraph(root, style, scope)
            val doubled = buildReaktorFlowGraph(root, style.atDisplayDensity(2f), scope)
            assertEquals(baseline.nodes.map { it.id }, doubled.nodes.map { it.id })
            baseline.nodes.zip(doubled.nodes).forEach { (expected, actual) ->
                assertEquals(expected.position.x, actual.position.x / 2, 0.00001)
                assertEquals(expected.position.y, actual.position.y / 2, 0.00001)
            }
            baseline.regions.zip(doubled.regions).forEach { (expected, actual) ->
                assertEquals(expected.width, actual.width / 2, 0.00001)
                assertEquals(expected.height, actual.height / 2, 0.00001)
            }
            assertNoNodeOverlap(doubled)
        }
    }

    @Test
    fun relocatingExpandedScopesMovesTheirNestedRegionsAndFoldedSummariesTogether() = fixture { root, child, _, _ ->
        val dependencies = koinApplication { }
        val adapter = KoinDependencyAdapter(dependencies)
        val nested = Graph(parentGraph = child, dependencyAdapter = adapter, label = "Nested")
        val leaf = Graph(parentGraph = nested, dependencyAdapter = adapter, label = "Leaf")
        try {
            nested.attach(BasicNode(nested))
            leaf.attach(BasicNode(leaf))
            nested.attach(ContainerNode(nested, "/leaf", arrayListOf(leaf)))
            child.attach(ContainerNode(child, "/nested", arrayListOf(nested)))
            listOf(1600.0 to 900.0, 900.0 to 1600.0).forEach { (width, height) ->
                val style = authored.copy(layout = authored.layout.copy(targetContentWidthPx = width, targetContentHeightPx = height))
                val scope = ReaktorFlowScopeView().expand("root/0").expand("root/0/0")
                val flow = buildReaktorFlowGraph(root, style, scope)
                assertNoNodeOverlap(flow)
                val summary = flow.nodes.single { it.id == "root/0/0/0" }
                listOf("root", "root/0", "root/0/0", "root/0/0/0").forEach { id ->
                    val enclosing = flow.regions.single { it.id == id }
                    assertTrue(summary.position.x >= enclosing.x && summary.position.y >= enclosing.y &&
                        summary.position.x + requireNotNull(summary.width) <= enclosing.x + enclosing.width &&
                        summary.position.y + requireNotNull(summary.height) <= enclosing.y + enclosing.height,
                        "Nested summary must stay inside relocated ancestor $id")
                }
            }
        } finally { leaf.close(); nested.close(); dependencies.close() }
    }

    private fun assertNoNodeOverlap(flow: ReaktorFlowGraph) {
        flow.nodes.forEachIndexed { index, first ->
            flow.nodes.drop(index + 1).forEach { second ->
                val overlapX = first.position.x < second.position.x + requireNotNull(second.width) &&
                    second.position.x < first.position.x + requireNotNull(first.width)
                val overlapY = first.position.y < second.position.y + requireNotNull(second.height) &&
                    second.position.y < first.position.y + requireNotNull(first.height)
                assertFalse(overlapX && overlapY, "Overlapping nodes: ${first.id} / ${second.id}")
            }
        }
    }

    private fun fixture(block: (Graph, Graph, Node, Node) -> Unit) {
        val koin = koinApplication { }
        val adapter = KoinDependencyAdapter(koin)
        val root = Graph(dependencyAdapter = adapter, label = "Application")
        val children = (0 until 8).map { index ->
            Graph(parentGraph = root, dependencyAdapter = adapter, label = "Feature $index").also { child ->
                repeat(if (index == 0) 16 else 2) { child.attach(BasicNode(child)) }
            }
        }
        try {
            repeat(13) { root.attach(BasicNode(root)) }
            var selectedRoute: Node? = null
            var selectedScreen: Node? = null
            repeat(4) { index ->
                val route = RouteNode<Payload, RouteBinding<Payload>>(root, "/screen/$index") { RouteBinding(Payload()) }
                val screen = Screen(root)
                root.attach(route)
                root.attach(screen)
                connect(screen.routeBinding, route.routeBinding)
                selectedRoute = route
                selectedScreen = screen
            }
            root.attach(ContainerNode(root, "/features", ArrayList(children)))
            block(root, children.first(), requireNotNull(selectedRoute), requireNotNull(selectedScreen))
        } finally {
            children.forEach(Graph::close)
            root.close()
            koin.close()
        }
    }

    private class Screen(graph: Graph) : BasicNode(graph), Node.Routable {
        override val routeBinding by consumes<RouteBinding<Payload>>()
    }
}
