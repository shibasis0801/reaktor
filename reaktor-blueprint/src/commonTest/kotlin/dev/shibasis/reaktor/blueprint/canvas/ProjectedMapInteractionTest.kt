package dev.shibasis.reaktor.blueprint.canvas

import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.test.*

class ProjectedMapInteractionTest {
    private val cards = listOf(card("a", 20.0, true), card("b", 80.0, true), card("c", 20.0, false), card("d", 80.0, false))
        .map { if (it.id in setOf("c", "d")) it.copy(x = 240.0) else it }.associateBy { it.id }
    private val frames = listOf(Frame("left", "Left", 0.0, 0.0, 100.0, 160.0, setOf("a", "b"), emptyList(), false),
        Frame("right", "Right", 220.0, 0.0, 100.0, 160.0, setOf("c", "d"), emptyList(), false))
    private val links = listOf(link("ac", "a", "c", 30.0), link("bd", "b", "d", 90.0))
    private val full = BlueprintLayout("source", cards, frames, links, 320.0, 160.0)
    private fun levels(source: BlueprintLayout<String> = full) = BlueprintMapLevels.prepare(source, { if (it.id.startsWith("summary:")) .1 else 1.0 })

    @Test fun summarySelectsTheRealGroupAndNeverCreatesANode() {
        val interaction = ProjectedMapInteraction(full, levels().at(.5))
        assertEquals(Selection(notes = setOf("left")), interaction.card("summary:left")?.selection)
        assertEquals(Selection(nodes = setOf("a")), interaction.card("a")?.selection)
        assertNull(interaction.card("summary:absent"))
        assertSame(frames[0], interaction.targetBounds("summary:left"))
        assertFalse(interaction.card("summary:left")!!.selection.nodes.contains("summary:left"))
    }

    @Test fun singletonBoundaryResolvesTheExactOriginalRowTypeAndDirection() {
        val source = full.copy(links = listOf(links[0]))
        val level = levels(source).at(.5)
        val interaction = ProjectedMapInteraction(source, level)
        for ((boundary, refs) in level.forwardedPorts) {
            assertEquals(1, refs.size)
            val original = refs.single()
            val resolved = assertNotNull(interaction.pin(boundary))
            assertEquals(setOf(original.card), resolved.selection.nodes)
            val (card, pin) = assertNotNull(resolved.pin)
            assertSame(source.cards[original.card], card)
            assertSame(card.pins.single(), pin)
            assertEquals("exact|row", pin.key)
            assertEquals("Record", pin.type)
            assertEquals(original.provides, pin.provides)
            assertEquals(64.0, pin.y)
        }
    }

    @Test fun multiMemberBoundaryAndCorridorPreserveAllOriginalWiresWithoutChoosingAPin() {
        val level = levels().at(.5)
        val interaction = ProjectedMapInteraction(full, level)
        val corridor = level.layout.links.single()
        assertEquals(setOf("ac", "bd"), interaction.wire(corridor)?.selection?.wires)
        level.forwardedPorts.keys.forEach { boundary ->
            val resolved = assertNotNull(interaction.pin(boundary))
            assertEquals(Selection(wires = setOf("ac", "bd")), resolved.selection)
            assertNull(resolved.pin)
        }
    }

    @Test fun separateBoundariesAndOriginalLinksKeepTheirExactWireIdentities() {
        val level = levels().at(.5, bundles = false)
        val interaction = ProjectedMapInteraction(full, level)
        level.layout.links.forEach { projected ->
            assertEquals(setOf(projected.id), interaction.wire(projected)?.selection?.wires)
            val ref = PinRef(projected.from, projected.fromPort!!, true)
            assertEquals(projected.id.first().toString(), interaction.pin(ref)?.pin?.first?.id)
        }
        val near = ProjectedMapInteraction(full, levels().at(2.0))
        assertEquals(setOf("ac"), near.wire(links[0])?.selection?.wires)
        assertFalse(near.projectedWire(links[0]))
    }

    @Test fun invalidForwardingCannotSelectAFallbackLeafOrInventAType() {
        val level = levels().at(.5)
        val boundary = level.forwardedPorts.keys.first()
        val invalid = level.copy(forwardedPorts = level.forwardedPorts + (boundary to listOf(PinRef("missing", "exact|row", true))))
        assertNull(ProjectedMapInteraction(full, invalid).pin(boundary))
        val mistyped = full.copy(cards = full.cards + ("a" to cards.getValue("a").copy(pins = listOf(cards.getValue("a").pins.single().copy(type = "Other")))))
        assertNull(ProjectedMapInteraction(mistyped, level).pin(boundary))
        assertNull(ProjectedMapInteraction(full, level).wire(level.layout.links.single().copy(members = listOf("invented"))))
        assertNull(ProjectedMapInteraction(full, level).pin(PinRef("summary:left", "absent", true)))
    }

    @Test fun pointerAndSemanticTargetsAgreeAtBothDensitiesAndRepeatedRevealKeepsFullGeometry() {
        val prepared = levels()
        val positions = full.cards.mapValues { it.value.let { c -> listOf(c.x, c.y, c.width, c.height, c.pins) } }
        val routes = full.links.associate { it.id to it.points }
        for (density in listOf(1.0, 2.0)) {
            val level = prepared.at(.5)
            val interaction = ProjectedMapInteraction(full, level)
            val box = level.layout.cards.getValue("summary:left")
            val observedZoom = .5
            val cameraX = 17.0
            val cameraY = -9.0
            val screenX = density * (cameraX + (box.x + box.width / 2) * observedZoom)
            val screenY = density * (cameraY + (box.y + box.height / 2) * observedZoom)
            val x = (screenX / density - cameraX) / observedZoom
            val y = (screenY / density - cameraY) / observedZoom
            val pointer = level.layout.cards.values.single { x > it.x && x < it.x + it.width && y > it.y && y < it.y + it.height }
            assertEquals(interaction.card(box.id), interaction.card(pointer.id))
            repeat(3) { assertSame(frames[0], interaction.targetBounds(box.id)); assertSame(cards["a"], interaction.targetBounds("a")) }
            assertEquals(positions, full.cards.mapValues { it.value.let { c -> listOf(c.x, c.y, c.width, c.height, c.pins) } })
            assertEquals(routes, full.links.associate { it.id to it.points })
            assertSame(full, prepared.full)
        }
    }

    private fun card(id: String, y: Double, provides: Boolean) = Card(id, 20.0, y, 40.0, 80.0,
        listOf(Pin("exact|row", "Record", provides, 64.0, PortWiring.Connected)), 0)
    private fun link(id: String, from: String, to: String, y: Double) = Link(id, from, to, "exact|row", "exact|row", LinkKind.Wire,
        listOf(id), listOf(60.0 to y, 100.0 to y, 220.0 to y, 240.0 to y), family = "Record")
}
