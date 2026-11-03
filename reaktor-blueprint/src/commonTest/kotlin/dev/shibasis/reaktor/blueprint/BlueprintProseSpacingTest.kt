package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.*

class BlueprintProseSpacingTest {
    private val lead = "bpo-lead"
    private val neighbours = listOf("bpo-agents-lead", "bpo-engine", "bpo-cartographer", "bpo-designer", "bpo-auditor")
    private val prose = mapOf(BlueprintFace.Key to "prose")
    private fun identity(id: String, connections: Int) = PortShape(id, "Agent", true,
        if (connections > 0) PortWiring.Connected else PortWiring.Unused, connections)
    private val graph = AppGraph(GraphShape(
        scopes = listOf(ScopeShape("team", "Team", null)),
        nodes = listOf(NodeShape(lead, "team", "blueprint lead", "Agent", NodeKind.Actor,
            ports = neighbours.map { PortShape("supervises $it", "Agent", false, PortWiring.Connected, 1) } + identity(lead, 0), attributes = prose)) +
            neighbours.map { NodeShape(it, "team", it, "Agent", NodeKind.Actor, ports = listOf(identity(it, 1)), attributes = prose) } +
            NodeShape("note", "team", "Note", "Note", NodeKind.Node, attributes = prose),
        wires = neighbours.map { WireShape(consumer = lead, consumerPort = "supervises $it", provider = it, providerPort = it, type = "Agent") },
        routes = emptyList(),
    ))

    @Test fun fiveNeighbourTitleRowsAndFooterHaveDisjointFixedBands() {
        val layout = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true))
        val card = layout.cards.getValue(lead)
        assertEquals(238.0, card.height)
        assertEquals(112.0, BlueprintEngine.ProseHeaderHeight)
        assertEquals(36.0, BlueprintEngine.ProseFooterHeight)
        assertEquals(neighbours.map { "supervises $it" }.toSet(), card.pins.filter { !it.provides }.map { it.key }.toSet())
        assertEquals(listOf(121.0, 139.0, 157.0, 175.0, 193.0), card.pins.filter { !it.provides }.map { it.y })
        assertEquals(121.0, card.pins.single { it.provides }.y)
        assertTrue(card.pins.all { it.type == "Agent" })
        assertEquals(lead, card.pins.single { it.provides }.key)
        val titleTop = 34.0
        val titleBottom = BlueprintEngine.ProseHeaderHeight - 6.0
        val rowTop = card.pins.minOf { it.y } - BlueprintEngine.RowHeight / 2
        val rowBottom = card.pins.maxOf { it.y } + BlueprintEngine.RowHeight / 2
        val footerTop = card.height - BlueprintEngine.ProseFooterHeight
        val subtitleTop = card.height - 28.0
        assertTrue(titleTop < titleBottom && titleBottom < rowTop)
        assertEquals(202.0, rowBottom)
        assertEquals(rowBottom, footerTop)
        assertTrue(rowBottom < subtitleTop && subtitleTop + 18.0 <= card.height)
        graph.wiresById.forEach { (id, wire) ->
            val link = layout.links.single { id in it.members }
            assertEquals(listOf(id), link.members)
            assertEquals(listOf(wire.provider, wire.providerPort, wire.consumer, wire.consumerPort),
                listOf(link.from, link.fromPort, link.to, link.toPort))
            val from = layout.cards.getValue(link.from)
            val to = layout.cards.getValue(link.to)
            assertEquals(BlueprintEngine.anchor(layout.cards, from.id, link.fromPort, true), link.points.first())
            assertEquals(BlueprintEngine.anchor(layout.cards, to.id, link.toPort, false), link.points.last())
        }
    }

    @Test fun zoomAndSelectionKeepEveryOriginalAnchorRouteAndCard() {
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports, nested = true))
        val levels = BlueprintMapLevels.prepare(full, titleZoom = { 1.0 })
        val geometry = full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height) }
        val pins = full.cards.mapValues { it.value.pins }
        val points = full.links.associate { it.id to it.points }
        var expanded = emptySet<String>()
        listOf(.01, .1, .5, 1.2, 2.0, 16.0).forEach { zoom ->
            val level = levels.at(zoom, expanded)
            expanded = level.expanded
            level.layout.cards.filterKeys { it in full.cards }.forEach { (id, card) -> assertSame(full.cards.getValue(id), card) }
            val visible = level.layout.links.flatMap { it.members }.toSet()
            assertTrue(visible.intersect(level.layout.foldedLinks.keys).isEmpty())
            assertEquals(graph.wiresById.keys, visible + level.layout.foldedLinks.keys)
            full.cards.values.forEach { card -> card.pins.forEach { pin ->
                val selection = card.ref(pin)
                assertTrue(selection.matches(card.id, pin))
                assertSame(card, levels.targetBounds(selection.card))
            } }
            assertEquals(geometry, full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height) })
            assertEquals(pins, full.cards.mapValues { it.value.pins })
            assertEquals(points, full.links.associate { it.id to it.points })
        }
        assertSame(full, levels.at(16.0).layout)
    }

    @Test fun legacyAndPortlessCompactGeometryStaysUnchanged() {
        val ordinary = BlueprintNode("ordinary", 0, inputs = listOf(PinSpec("in", "Agent", PortWiring.Unmatched)))
        assertEquals(72.0, BlueprintEngine.cardHeight(ordinary))
        assertEquals(55.0, BlueprintEngine.pinsOf(ordinary).single().y)
        assertEquals(246.0, BlueprintEngine.cardHeight(ordinary.copy(body = BlueprintBody(272.0, 200.0))))
        val full = AppGraphLayout.layout(graph, LayoutRequest(Detail.Ports))
        assertEquals(144.0, full.cards.getValue("note").height)
        assertTrue(full.cards.getValue("note").pins.isEmpty())
        val compact = AppGraphLayout.layout(graph, LayoutRequest(Detail.Compact))
        compact.cards.values.forEach { assertEquals(144.0, it.height); assertTrue(it.pins.isEmpty()) }
        val spaced = ordinary.copy(pinHeader = BlueprintEngine.ProseHeaderHeight, pinFooter = BlueprintEngine.ProseFooterHeight)
        assertEquals(166.0, BlueprintEngine.cardHeight(spaced))
        assertEquals(121.0, BlueprintEngine.pinsOf(spaced).single().y)
        assertFailsWith<IllegalArgumentException> { ordinary.copy(pinHeader = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ordinary.copy(pinFooter = -1.0) }
    }
}
