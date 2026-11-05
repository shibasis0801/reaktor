package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.blueprint.BlueprintBody
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

class GraphEditorBodyTest {
    private val shape = GraphShape(listOf(ScopeShape("team", "Team")), listOf(
        NodeShape("a", "team", "Agent with a complete role", "Agent", NodeKind.Actor, ports = listOf(
            PortShape("supervises original complete typed relation", "Agent", false, PortWiring.Unmatched),
            PortShape("identity", "Agent", true, PortWiring.Connected))),
        NodeShape("b", "team", "Other agent", "Agent", NodeKind.Actor, ports = listOf(
            PortShape("identity", "Agent", false, PortWiring.Connected))),
    ), listOf(WireShape("a", "identity", "b", "identity", "Agent")), emptyList())
    private val placed = Placement(mapOf("a" to Spot(40.0, 80.0), "b" to Spot(1300.0, 80.0)))
    private val plain get() = GraphEditor(shape, placed, id = "body-test").also { it.showChanges = false }
    private val body = BlueprintBody(960.0, 360.0)


    @Test fun settlingAMovedCardReroutesCrossedAutomaticWiresAndKeepsManualAuthority() {
        val automatic = WireShape("b", "automatic", "a", "automatic", "Agent")
        val manual = WireShape("b", "manual", "a", "manual", "Agent")
        val source = GraphShape(listOf(ScopeShape("team", "Team")), listOf(
            NodeShape("a", "team", "Provider", "Agent", NodeKind.Actor, ports = listOf(
                PortShape("automatic", "Agent", true, PortWiring.Connected),
                PortShape("manual", "Agent", true, PortWiring.Connected))),
            NodeShape("b", "team", "Consumer", "Agent", NodeKind.Actor, ports = listOf(
                PortShape("automatic", "Agent", false, PortWiring.Connected),
                PortShape("manual", "Agent", false, PortWiring.Connected))),
            NodeShape("c", "team", "Moved obstacle", "Agent", NodeKind.Actor),
        ), listOf(automatic, manual), emptyList())
        val initial = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(1000.0, 0.0), "c" to Spot(448.0, 400.0)),
            bends = mapOf(manual.id to listOf(Spot(600.0, 260.0))))
        val editor = GraphEditor(source, initial).also { it.showChanges = false; accept(it) }
        val before = editor.settled
        val manualTrace = before.links.single { it.id == manual.id }.trace
        assertTrue(editor.press(472f, 420f, androidx.compose.ui.geometry.Offset.Zero, 4f,
            androidx.compose.ui.input.pointer.PointerKeyboardModifiers(), 1, false))
        editor.drag(472f, 20f, androidx.compose.ui.geometry.Offset.Zero)
        editor.layout // Cache the final preview coordinates before release.
        editor.release(472f, 20f, androidx.compose.ui.geometry.Offset.Zero)
        val after = editor.settled
        val obstacle = after.cards.getValue("c")
        val rerouted = after.links.single { it.id == automatic.id }
        assertFalse(rerouted.trace.zipWithNext().any { (a, b) ->
            maxOf(a.first, b.first) > obstacle.x && minOf(a.first, b.first) < obstacle.x + obstacle.width &&
                maxOf(a.second, b.second) > obstacle.y && minOf(a.second, b.second) < obstacle.y + obstacle.height
        })
        assertEquals(source, editor.shape)
        assertEquals(initial.cards.getValue("a"), editor.placement.cards.getValue("a"))
        assertEquals(initial.cards.getValue("b"), editor.placement.cards.getValue("b"))
        assertEquals(initial.bends, editor.placement.bends)
        assertEquals(manualTrace, after.links.single { it.id == manual.id }.trace)
        assertEquals(before.links.map { Triple(it.id, it.fromPort to it.toPort, it.members) }, after.links.map { Triple(it.id, it.fromPort to it.toPort, it.members) })
        assertEquals(Selection(setOf("c")), editor.selection)
        assertEquals(1, editor.history.size)
        val moved = editor.placement
        editor.undo()
        assertEquals(initial, editor.placement)
        assertEquals(source, editor.shape)
        assertTrue(editor.canRedo)
        editor.redo()
        assertEquals(moved, editor.placement)
        assertEquals(after.links, editor.settled.links)
    }

    @Test fun addingInDensePlacementFindsDiagonalAndDistantSpaceAndRemainsUndoable() {
        val cases = listOf(
            listOf(Triple("square", Spot(-1800.0, -1800.0), BlueprintBody(3600.0, 3600.0))),
            listOf(Triple("horizontal", Spot(-1600.0, 0.0), BlueprintBody(3600.0, 200.0)),
                Triple("vertical", Spot(0.0, -1600.0), BlueprintBody(400.0, 3600.0))),
        )
        for ((index, obstacles) in cases.withIndex()) {
            val source = GraphShape(listOf(ScopeShape("team", "Team")), obstacles.map { (id, _, _) ->
                NodeShape(id, "team", id, "Agent", NodeKind.Actor)
            }, emptyList(), emptyList())
            val initial = Placement(obstacles.associate { (id, spot, _) -> id to spot })
            val editor = GraphEditor(source, initial, bodies = obstacles.associate { (id, _, body) -> id to body })
                .also { it.showChanges = false; accept(it) }
            val before = editor.settled
            val id = editor.add(Template("New task", NodeKind.Actor, "Agent", emptyList()), Spot(180.0, 20.0))
            val placed = editor.placement
            val card = editor.settled.cards.getValue(id)
            assertTrue(before.cards.values.none { box ->
                card.x < box.x + box.width + dev.shibasis.reaktor.blueprint.BlueprintEngine.CardGap &&
                    card.x + card.width + dev.shibasis.reaktor.blueprint.BlueprintEngine.CardGap > box.x &&
                    card.y < box.y + box.height + dev.shibasis.reaktor.blueprint.BlueprintEngine.CardGap &&
                    card.y + card.height + dev.shibasis.reaktor.blueprint.BlueprintEngine.CardGap > box.y
            })
            if (index == 0) assertTrue(maxOf(kotlin.math.abs(card.x), kotlin.math.abs(card.y)) > 1280.0)
            else { assertTrue(card.x != 0.0 && card.y != 0.0); assertTrue(maxOf(kotlin.math.abs(card.x), kotlin.math.abs(card.y)) < 1280.0) }
            assertEquals(source.nodes, editor.shape.nodes.filter { it.id != id })
            assertEquals(initial.cards, editor.placement.cards.filterKeys { it != id })
            assertEquals(Selection(setOf(id)), editor.selection)
            assertEquals(1, editor.history.size)
            editor.undo()
            assertEquals(source, editor.shape)
            assertEquals(initial, editor.placement)
            editor.redo()
            assertEquals(placed, editor.placement)
            assertEquals(card, editor.settled.cards.getValue(id))
        }
    }

    private fun accept(editor: GraphEditor) {
        editor.editRevision = EditRevision("source:7", "presentation:3")
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        editor.commit = { _, revision -> EditReceipt.Accepted(revision) }
        editor.savePlacement = { _, revision -> EditReceipt.Accepted(revision) }
    }

    @Test fun absentEnvelopesKeepTheDefaultProjectionExactly() {
        val original = plain.settled
        val explicit = GraphEditor(shape, placed, id = "body-test", bodies = emptyMap()).also { it.showChanges = false }.settled
        assertEquals(original, explicit)
    }

    @Test fun thePaintedEnvelopeIsAlsoTheEditableHitEnvelope() {
        val pins = plain.settled.cards.mapValues { it.value.pins }
        val editor = GraphEditor(shape, placed, pins = { pins.getValue(it.id) }, bodies = mapOf("a" to body)).also { it.showChanges = false }
        val card = editor.settled.cards.getValue("a")
        assertEquals(body.width, card.width)
        assertEquals(body.height, card.height)
        assertEquals(placed.cards.getValue("a"), Spot(card.x, card.y))
        assertSame(pins.getValue("a").first(), card.pins.first())
        assertEquals(shape.nodes.flatMap { it.ports }.map { Triple(it.key, it.type, it.provides) }.toSet(),
            editor.shape.nodes.flatMap { it.ports }.map { Triple(it.key, it.type, it.provides) }.toSet())
        assertEquals(shape.wires.map { it.id }.toSet(), editor.settled.links.flatMap { it.members }.toSet())
        assertSame(card, editor.cardAt(Spot(card.x + card.width - 1, card.y + card.height - 1)))
        assertEquals(placed, editor.placement)
        assertFalse(editor.dirty)
    }

    @Test fun anEnvelopeIsCopiedForItsSourceVersionAndCannotShrinkTheDefault() {
        val mutable = mutableMapOf("a" to body, "b" to BlueprintBody(1.0, 1.0))
        val editor = GraphEditor(shape, placed, bodies = mutable).also { it.showChanges = false }
        mutable.clear()
        assertEquals(body.width, editor.settled.cards.getValue("a").width)
        assertEquals(plain.settled.cards.getValue("b"), editor.settled.cards.getValue("b"))
    }

    @Test fun staleAndRefusedWritesKeepTheOriginalEnvelopeAndHistory() {
        val editor = GraphEditor(shape, placed, bodies = mapOf("a" to body)).also { it.showChanges = false }
        val full = editor.settled
        val before = editor.shape
        val revision = EditRevision("source:7", "presentation:3")
        editor.editRevision = revision
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        var received: EditRevision? = null
        editor.commit = { _, expected -> received = expected; EditReceipt.Refused("Source changed", stale = true) }
        editor.rename("a", "Proposed role")
        assertEquals(revision, received)
        assertEquals(before, editor.shape)
        assertEquals(full, editor.settled)
        assertEquals(emptyList(), editor.history)
        editor.permits = { _, _ -> false }
        editor.rename("a", "Refused role")
        assertEquals(before, editor.shape)
        assertEquals(placed, editor.placement)
        assertFalse(editor.canUndo)
    }

    @Test fun acceptedEditsKeepRevisionAndUndoAuthorityWithTheEnvelope() {
        val editor = GraphEditor(shape, placed, bodies = mapOf("a" to body)).also { it.showChanges = false }
        val expected = EditRevision("source:7", "presentation:3")
        editor.editRevision = expected
        editor.permits = { _, _ -> true }
        editor.validate = { Verdict(true, "") }
        var calls = 0
        editor.commit = { _, revision -> assertEquals(expected, revision); calls++; EditReceipt.Accepted(expected) }
        editor.rename("a", "Accepted role")
        assertEquals("Accepted role", editor.shape.node("a")?.label)
        assertTrue(editor.canUndo)
        assertEquals(body.width, editor.settled.cards.getValue("a").width)
        editor.undo()
        assertEquals(shape.node("a")?.label, editor.shape.node("a")?.label)
        assertEquals(body.height, editor.settled.cards.getValue("a").height)
        assertEquals(2, calls)
    }
}
