package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.GraphSource
import dev.shibasis.reaktor.blueprint.graph.Hop
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BlueprintLinkIdentityTest {
    @Test
    fun delimiterContainingEndpointsDoNotAliasInJoins() {
        val edges = listOf(
            BlueprintEdge("wire-one", "a", "b>|c"),
            BlueprintEdge("wire-two", "a>|b", "c"),
            BlueprintEdge("route-one", "a>b", "c", kind = LinkKind.Route),
            BlueprintEdge("route-two", "a", "b>c", kind = LinkKind.Route),
        )
        val joined = BlueprintEngine.join(edges, edges.flatMap { listOf(it.from, it.to) }.associateWith { emptyList() })
        assertEquals(4, joined.size)
        assertEquals(4, joined.map { it.id }.toSet().size)
        assertEquals(edges.map { it.id }.toSet(), joined.flatMap { it.members }.toSet())
        assertEquals(joined, BlueprintEngine.join(edges, edges.flatMap { listOf(it.from, it.to) }.associateWith { emptyList() }))
    }

    @Test
    fun bundlesKeepFamilyDirectionKindAndRoutingDistinct() {
        val first = BlueprintEdge("uses-one", "a", "b", family = "uses")
        val edges = listOf(first, first.copy(id = "uses-two"), first.copy(id = "studies", family = "studies"),
            first.copy(id = "reverse", reversed = true), first.copy(id = "opposite", from = "b", to = "a"),
            first.copy(id = "curved", curved = true), first.copy(id = "route", kind = LinkKind.Route))
        val joined = BlueprintEngine.join(edges, mapOf("a" to emptyList(), "b" to emptyList()))
        assertEquals(6, joined.size)
        assertEquals(listOf("uses-one", "uses-two"), joined.single { it.members.size == 2 }.members)
        assertEquals(edges.size, joined.sumOf { it.members.size })
        assertEquals(edges.map { it.id }.toSet(), joined.flatMap { it.members }.toSet())
        assertEquals(6, joined.associateBy { it.id }.size)
    }

    @Test
    fun routeExitAndEscapedEndpointKeysRemainDistinct() {
        val hops = listOf(Hop("a>b", "c"), Hop("a", "b>c"), Hop("a", "b:c"), Hop("a", "b", "c"),
            Hop("a%3Eb", "c"), Hop("a", "b", "c:d"))
        assertEquals(hops.size, hops.map { it.id }.toSet().size)
        assertEquals("a>b", Hop("a", "b").id)
        assertEquals("a>b:done", Hop("a", "b", "done").id)
    }

    @Test
    fun sourceWireTupleSurvivesDelimiterCollisionAndIndexLookup() {
        val wires = listOf(WireShape("d", "in", "a.b", "c", "Flow"), WireShape("d", "in", "a", "b.c", "Flow"))
        val shape = GraphShape(listOf(ScopeShape("root", "Root")),
            listOf(node("a.b", output = "c"), node("a", output = "b.c"), node("d")), wires, emptyList())
        val graph = AppGraph(shape)
        assertEquals(2, graph.wires.size)
        assertEquals(2, graph.wiresById.size)
        assertNotEquals(graph.wires[0].id, graph.wires[1].id)
        graph.wires.forEach { assertEquals(it, graph.wiresById[it.id]) }
        assertEquals(wires, graph.shape.wires)
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard), LayeredFrameLayouter)
        assertEquals(graph.wires.map { it.id }.toSet(), layout.links.flatMap { it.members }.toSet())
    }

    @Test
    fun hidingARouteDoesNotEraseASeparateSourceWire() {
        val route = node("a-route").copy(kind = NodeKind.Route)
        val screen = node("a").copy(kind = NodeKind.Screen, attachedTo = route.id)
        val shape = GraphShape(listOf(ScopeShape("root", "Root")), listOf(route, screen, node("b")),
            listOf(WireShape("b", "in", route.id, "out", "Flow"), WireShape("b", "in", screen.id, "out", "Flow")), emptyList())
        val desktop = AppGraph(shape)
        val system = AppGraph(shape, GraphSource.System)
        assertEquals(2, desktop.wires.size)
        assertEquals(2, desktop.wiresById.size)
        assertTrue(desktop.wires.all { it.provider == "a" })
        assertEquals(system.wires.map { it.id }.toSet(), desktop.wires.map { it.id }.toSet())
        assertEquals(shape.wires.size, desktop.wiresOut("a").size)
    }

    @Test
    fun realSelfWiresAndDelayedTransitionsSurviveLayout() {
        val route = node("a-route").copy(kind = NodeKind.Route)
        val screen = node("a").copy(kind = NodeKind.Screen, attachedTo = route.id)
        val shape = GraphShape(listOf(ScopeShape("root", "Root")), listOf(route, screen),
            listOf(WireShape("a", "in", "a", "out", "Flow")), listOf(RouteShape(route.id, route.id, "later")))
        val graph = AppGraph(shape)
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard), LayeredFrameLayouter)
        assertEquals(1, graph.wires.size)
        assertEquals(1, graph.hops.size)
        assertEquals(2, layout.links.size)
        assertEquals(setOf(graph.wires.single().id, graph.hops.single().id), layout.links.flatMap { it.members }.toSet())
        assertTrue(layout.links.all { it.from == "a" && it.to == "a" && it.trace.size >= 2 })
        assertTrue(layout.links.flatMap { it.trace }.all { it.first.isFinite() && it.second.isFinite() })
        assertEquals(shape, graph.shape)
    }

    @Test
    fun internalFoldedLinksAreNotPaintedAsVisibleSelfLoops() {
        val shape = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", "root"), ScopeShape("right", "Right", "root")),
            listOf(node("a").copy(scope = "left"), node("b").copy(scope = "left"), node("c").copy(scope = "right")),
            listOf(WireShape("b", "in", "a", "out", "Flow"), WireShape("c", "in", "a", "out", "Flow")), emptyList())
        val graph = AppGraph(shape)
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands), LayeredFrameLayouter)
        assertEquals(2, graph.wires.size)
        assertEquals("left", layout.cardOf["a"])
        assertEquals("left", layout.cardOf["b"])
        assertEquals(1, layout.links.size)
        assertEquals(listOf(graph.wires.single { it.consumer == "c" }.id), layout.links.single().members)
        assertTrue(layout.links.none { it.from == it.to })
        assertEquals(mapOf(graph.wires.single { it.consumer == "b" }.id to "left"), layout.foldedLinks)
        assertEquals(graph.nodes.keys, layout.cardOf.keys)
    }

    @Test
    fun layoutHintsUseEndpointPairsRatherThanDelimitedStrings() {
        val edges = BlueprintEngine.join(listOf(BlueprintEdge("one", "a|b", "c", curved = true), BlueprintEdge("two", "a", "b|c", curved = true)),
            listOf("a|b", "c", "a", "b|c").associateWith { emptyList() })
        assertEquals(setOf("a|b" to "c", "a" to "b|c"), BlueprintEngine.hintPairs(edges, emptyList(), 1).toSet())
        assertEquals(listOf("a" to "b|c"), BlueprintEngine.hintPairs(edges, listOf(edges.first().copy(curved = false)), 1))
    }

    @Test
    fun foldedConnectionsKeepDistinctFamiliesAndDirections() {
        val nodes = listOf("a" to "left", "b" to "right").map { (id, scope) -> node(id).copy(scope = scope,
            ports = node(id).ports + listOf(PortShape("requestOut", "Request", true, PortWiring.Connected, 1), PortShape("requestIn", "Request", false, PortWiring.Connected, 1))) }
        val shape = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", "root"), ScopeShape("right", "Right", "root")), nodes,
            listOf(WireShape("b", "in", "a", "out", "Flow"), WireShape("b", "requestIn", "a", "requestOut", "Request"), WireShape("a", "in", "b", "out", "Flow")), emptyList())
        val graph = AppGraph(shape)
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands), LayeredFrameLayouter)
        assertEquals(3, layout.links.size)
        assertEquals(setOf(Triple("left", "right", "Flow"), Triple("left", "right", "Request"), Triple("right", "left", "Flow")),
            layout.links.map { Triple(it.from, it.to, it.family) }.toSet())
        assertEquals(graph.wires.map { it.id }.toSet(), layout.links.flatMap { it.members }.toSet())
        assertEquals(shape.wires.size, layout.links.sumOf { it.members.size })
    }

    @Test
    fun foldingAWorkflowHidesItsMembersSelfLinksUntilOpened() {
        val flow = node("flow").copy(kind = NodeKind.Workflow, attributes = mapOf("workflow" to "true"))
        val child = node("child").copy(scope = "inside")
        val shape = GraphShape(listOf(ScopeShape("root", "Root"), ScopeShape("inside", "Inside", "root", container = flow.id)), listOf(flow, child),
            listOf(WireShape("flow", "in", "flow", "out", "Flow"), WireShape("child", "in", "child", "out", "Flow")), emptyList())
        val graph = AppGraph(shape)
        val folded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard), LayeredFrameLayouter)
        assertEquals("flow", folded.cardOf["child"])
        assertEquals(listOf(graph.wires.single { it.provider == "flow" }.id), folded.links.flatMap { it.members })
        val opened = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard, expandedFlows = setOf("flow")), LayeredFrameLayouter)
        assertEquals(graph.wires.map { it.id }.toSet(), opened.links.flatMap { it.members }.toSet())
        assertEquals("child", opened.cardOf["child"])
        assertEquals(mapOf(graph.wires.single { it.provider == "child" }.id to "flow"), folded.foldedLinks)
        assertTrue(opened.foldedLinks.isEmpty())
        assertEquals(shape, graph.shape)
    }

    @Test
    fun nestedRequestsKeepScopeKeysAndInternalLinksReturnWhenOpened() {
        val scopes = listOf(ScopeShape("root", "Root"), ScopeShape("left", "Left", "root"), ScopeShape("right", "Right", "root"))
        val nodes = listOf(node("a").copy(scope = "left"), node("b").copy(scope = "left"), node("c").copy(scope = "right"))
        val shape = GraphShape(scopes, nodes, listOf(WireShape("b", "in", "a", "out", "Flow"), WireShape("c", "in", "a", "out", "Flow")), emptyList())
        val graph = AppGraph(shape)
        for (layouter in listOf(LayeredFrameLayouter, DefaultFrameLayouter)) {
            val nested = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard, nested = true), layouter)
            assertEquals(scopes.map { "feature:${it.id}" }.toSet(), nested.frames.map { it.key }.toSet())
            val folded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands), layouter)
            assertEquals(2, folded.cards.getValue("left").folded)
            assertEquals(1, folded.links.size)
            val expanded = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard, nested = true), layouter)
            assertEquals(graph.wires.map { it.id }.toSet(), expanded.links.flatMap { it.members }.toSet())
            assertEquals(shape, graph.shape)
        }
    }

    private fun node(id: String, output: String = "out") = NodeShape(id, "root", id, "Node", NodeKind.Node,
        ports = listOf(PortShape(output, "Flow", true, PortWiring.Connected, 1), PortShape("in", "Flow", false, PortWiring.Connected, 1)))
}
