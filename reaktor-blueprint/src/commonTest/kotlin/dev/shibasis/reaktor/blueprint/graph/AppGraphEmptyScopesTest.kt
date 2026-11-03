package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppGraphEmptyScopesTest {
    private val scopes = listOf(
        ScopeShape("root", "Code"),
        ScopeShape("app", "Application", "root"),
        ScopeShape("empty", "Empty code island", "root"),
        ScopeShape("parent", "Nested code island", "root"),
        ScopeShape("child", "Empty package", "parent"),
        ScopeShape("failed", "Unreadable island", "root"),
    )
    private val client = NodeShape("client", "app", "Client", "Client", NodeKind.Service, ports = listOf(
        PortShape("raw.in|:#", "DeclaredRecord", false, PortWiring.Connected, 1)))
    private val unreadable = NodeShape("unreadable", "failed", "Read unavailable", "Unknown", NodeKind.Node,
        ports = listOf(PortShape("raw.out|:#", "DeclaredRecord", true, PortWiring.Connected, 1)))
    private val shape = GraphShape(scopes, listOf(client, unreadable), listOf(
        WireShape("client", "raw.in|:#", "unreadable", "raw.out|:#", "DeclaredRecord", sourceId = "wire|:#")), emptyList())
    private val graph = AppGraph(shape, GraphSource.Declared("code", "Code"))

    @Test fun organizationalRootUsesCompactConnectedSystemPlacementWithoutChangingHierarchyOrTypedWires() {
        val children = List(12) { index -> ScopeShape("system-$index", "System $index", "catalog") }
        val nodes = children.mapIndexed { index, scope -> NodeShape("node-$index", scope.id, "Node $index", "Component", NodeKind.Node,
            ports = listOf(PortShape("raw:out", "Payload", true, PortWiring.Connected),
                PortShape("raw:in", "Payload", false, PortWiring.Connected))) }
        val wires = (1 until nodes.size).map { index -> WireShape("node-$index", "raw:in", "node-${index - 1}", "raw:out",
            "Payload", sourceId = "edge-$index") }
        val source = GraphShape(listOf(ScopeShape("catalog", "Catalog")) + children, nodes, wires, emptyList())
        val app = AppGraph(source, GraphSource.Declared("connected-catalog", "Connected catalog"))
        val request = LayoutRequest(Detail.Ports, nested = true, bundles = true)
        val compact = AppGraphLayout.layout(app, request)
        val childGroups = children.map { scope -> dev.shibasis.reaktor.blueprint.BlueprintGroup("feature:${scope.id}", scope.label,
            nodes.filter { it.scope == scope.id }.map { AppGraphLayout.node(app, it, Detail.Ports) }) }
        val organizational = dev.shibasis.reaktor.blueprint.BlueprintGroup("feature:catalog", "Catalog",
            childGroups.map { group -> dev.shibasis.reaktor.blueprint.BlueprintNode(group.key, 0, group = group) })
        val nested = dev.shibasis.reaktor.blueprint.BlueprintEngine.layout(request, listOf(organizational),
            app.wires.map { wire -> dev.shibasis.reaktor.blueprint.BlueprintEdge(wire.id, wire.provider, wire.consumer,
                wire.providerPort, wire.consumerPort, family = wire.type) }, request.aspect)
        fun reach(layout: GraphLayout) = maxOf(layout.width / request.aspect, layout.height)
        assertTrue(reach(compact) < reach(nested) * .9, "Connected child systems should wrap instead of stretching one organizational layer")
        val work = AppGraphLayout.layout(app, request.copy(after = "after "))
        assertTrue(reach(work) < reach(nested) * .9, "Enabling Work dependency order must not stretch an organizational parent without dependencies")
        assertEquals(compact.cards.keys, work.cards.keys)
        assertEquals(compact.cards.mapValues { (_, card) -> card.pins }, work.cards.mapValues { (_, card) -> card.pins })
        assertEquals(compact.frames.map { it.key to it.parent }.toSet(), work.frames.map { it.key to it.parent }.toSet())
        assertEquals(compact.links.flatMap { it.members }.toSet(), work.links.flatMap { it.members }.toSet())
        assertEquals((listOf("catalog") + children.map { it.id }).map { "feature:$it" }.toSet(), compact.frames.map { it.key }.toSet())
        assertTrue(compact.frames.single { it.key == "feature:catalog" }.nodes.isEmpty())
        children.forEachIndexed { index, scope ->
            val frame = compact.frames.single { it.key == "feature:${scope.id}" }
            assertEquals("feature:catalog", frame.parent)
            assertEquals(setOf("node-$index"), frame.nodes)
        }
        assertEquals(nodes.map { it.id }.toSet(), compact.cards.keys)
        assertEquals(app.wires.map { it.id }.toSet(), compact.links.flatMap { it.members }.toSet())
        compact.links.forEach { link ->
            val raw = app.wires.single { it.id == link.members.single() }
            assertEquals(raw.provider, link.from); assertEquals(raw.consumer, link.to)
            assertEquals(raw.providerPort, link.fromPort); assertEquals(raw.consumerPort, link.toPort)
            assertEquals(dev.shibasis.reaktor.blueprint.BlueprintEngine.anchor(compact.cards, link.from, link.fromPort, true), link.trace.first())
            assertEquals(dev.shibasis.reaktor.blueprint.BlueprintEngine.anchor(compact.cards, link.to, link.toPort, false), link.trace.last())
        }
        compact.cards.forEach { (id, card) ->
            assertEquals(app.nodes.getValue(id).ports.map { Triple(it.key, it.type, it.provides) }.toSet(),
                card.pins.map { Triple(it.key, it.type, it.provides) }.toSet())
        }
        assertSame(source, app.shape)
    }

    @Test fun organizationalParentsKeepDefaultLanesWhileActualDependenciesKeepTheirRanks() {
        val scopes = listOf(ScopeShape("root", "Work"), ScopeShape("left", "Left", "root"), ScopeShape("right", "Right", "root"))
        val nodes = listOf(NodeShape("a0", "left", "Prepare", "Item", NodeKind.Workflow),
            NodeShape("a1", "left", "Review", "Item", NodeKind.Workflow),
            NodeShape("b0", "right", "Publish", "Item", NodeKind.Workflow))
        fun after(consumer: String, provider: String) = WireShape(consumer, "after $provider", provider, "ready", "Dependency")
        fun order(wires: List<WireShape>) = AppGraphOrder.of(
            AppGraph(GraphShape(scopes, nodes, wires, emptyList()), GraphSource.Declared("work-order", "Work")), "after ") { it }
        val left = dev.shibasis.reaktor.blueprint.BlueprintGroup("feature:left", "Left", listOf(
            dev.shibasis.reaktor.blueprint.BlueprintNode("a0", 7), dev.shibasis.reaktor.blueprint.BlueprintNode("a1", 8)))
        val right = dev.shibasis.reaktor.blueprint.BlueprintGroup("feature:right", "Right", listOf(
            dev.shibasis.reaktor.blueprint.BlueprintNode("b0", 9)))
        val parent = dev.shibasis.reaktor.blueprint.BlueprintGroup("feature:root", "Work", listOf(
            dev.shibasis.reaktor.blueprint.BlueprintNode(left.key, 3, group = left),
            dev.shibasis.reaktor.blueprint.BlueprintNode(right.key, 4, group = right)))
        val internal = listOf(after("a1", "a0"))
        val independent = order(internal).columns(parent)
        assertEquals(null, independent.lanes)
        assertEquals(listOf(3, 4), independent.nodes.map { it.lane })
        val nested = independent.nodes.first().group!!
        assertEquals(dev.shibasis.reaktor.blueprint.Lanes.Ranks, nested.lanes)
        assertEquals(listOf(0, 1), nested.nodes.map { it.lane })
        val dependent = order(internal + after("b0", "a1")).columns(parent)
        assertEquals(dev.shibasis.reaktor.blueprint.Lanes.Ranks, dependent.lanes)
        assertEquals(listOf(0, 1), dependent.nodes.map { it.lane })
        val cycle = order(internal + after("b0", "a1") + after("a0", "b0")).columns(parent)
        assertEquals(dev.shibasis.reaktor.blueprint.Lanes.Ranks, cycle.lanes)
        assertEquals(listOf(0, 0), cycle.nodes.map { it.lane })
        assertEquals(listOf(3, 4), parent.nodes.map { it.lane })
    }

    @Test fun genuineEmptyIslandsSurviveWithoutSyntheticNodes() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands))
        assertEquals(setOf("app", "empty", "parent", "failed"), layout.cards.keys)
        assertEquals(setOf("client", "unreadable"), layout.cardOf.keys)
        assertEquals("app", layout.cardOf["client"])
        assertEquals("failed", layout.cardOf["unreadable"])
        assertEquals(graph.wires.map { it.id }.toSet(), layout.links.flatMap { it.members }.toSet())
        assertEquals(setOf("client", "unreadable"), graph.nodes.keys)
        assertSame(shape, graph.shape)
        assertEquals(listOf("raw.in|:#"), graph.nodes.getValue("client").ports.map { it.key })
    }

    @Test fun nestedEmptyFramesKeepTheirActualParents() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true))
        val frames = layout.frames.associateBy { it.key }
        assertEquals(scopes.map { "feature:${it.id}" }.toSet(), frames.keys)
        assertEquals("feature:parent", frames.getValue("feature:child").parent)
        assertEquals("feature:root", frames.getValue("feature:parent").parent)
        assertTrue(frames.getValue("feature:child").nodes.isEmpty())
        assertEquals(setOf("client", "unreadable"), layout.cards.keys)
        val wire = layout.links.single()
        assertEquals("raw.out|:#", wire.fromPort)
        assertEquals("raw.in|:#", wire.toPort)
        assertEquals(graph.wires.map { it.id }, wire.members)
    }

    @Test fun hiddenScopesAndTheirEmptyDescendantsStayHidden() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, hidden = setOf("parent", "empty"), nested = true))
        assertFalse(layout.frames.any { it.key in setOf("feature:parent", "feature:child", "feature:empty") })
        assertEquals(setOf("client", "unreadable"), layout.cards.keys)
        val islands = AppGraphLayout.layout(graph, LayoutRequest(Detail.Islands, hidden = setOf("parent", "empty")))
        assertEquals(setOf("app", "failed"), islands.cards.keys)
    }

    @Test fun filteringContentDoesNotInventAnEmptyScope() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, hidden = setOf("client"), nested = true))
        assertFalse(layout.frames.any { it.key == "feature:app" })
        assertTrue(layout.frames.any { it.key == "feature:empty" })
        assertFalse("client" in layout.cards)
        assertTrue(layout.links.isEmpty())
    }

    @Test fun emptyScopesInsideFoldedWorkflowsRemainFoldedUntilExpanded() {
        val flow = NodeShape("flow", "root", "Workflow", "Workflow", NodeKind.Workflow, attributes = mapOf("workflow" to "flow"))
        val workflow = AppGraph(GraphShape(listOf(ScopeShape("root", "Code"),
            ScopeShape("inside", "Workflow body", "root", "flow"), ScopeShape("empty", "Empty stage", "inside")),
            listOf(flow), emptyList(), emptyList()), GraphSource.Declared("workflow", "Workflow"))
        val folded = AppGraphLayout.layout(workflow, LayoutRequest(Detail.Ports, nested = true))
        assertEquals(setOf("feature:root"), folded.frames.map { it.key }.toSet())
        assertEquals(setOf("flow"), folded.cards.keys)
        val expanded = AppGraphLayout.layout(workflow, LayoutRequest(Detail.Ports, nested = true, expandedFlows = setOf("flow")))
        assertTrue(expanded.frames.any { it.key == "feature:inside" })
        assertEquals("feature:inside", expanded.frames.single { it.key == "feature:empty" }.parent)
        assertEquals(setOf("flow"), expanded.cards.keys)
        assertTrue(expanded.links.isEmpty())
    }
}
