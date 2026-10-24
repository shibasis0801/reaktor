package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphDocument
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphGroup
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphNode
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphRow
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.CommandId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorFeaturesTest {
    private val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused), PortShape("text", "String", true, PortWiring.Unused))),
        NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
        NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
    ), emptyList(), emptyList())
    private val placement = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0), "c" to Spot(0.0, 200.0)))
    private val sink = Template("Sink", NodeKind.Service, "Sink", listOf(PortShape("in", "Int", false, PortWiring.Unmatched)))

    private fun drafted(): GraphPart {
        val editor = GraphEditor(shape, placement)
        editor.rename("b", "Beta")
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        val added = editor.add(sink, Spot(800.0, 400.0))
        assertTrue(editor.connect(PinEnd("c", "out", true), PinEnd(added, "in", false)).ok)
        return editor.graphPart()
    }

    @Test
    fun aDraftAppliedInCodeReopensWithNothingLeftToDo() {
        val part = drafted()
        assertFalse(shape.applied(part))
        val built = shape.replay(part).shape
        val added = built.nodes.single { it.label == "Sink" }.id
        val live = built.copy(nodes = built.nodes.map { if (it.id == added) it.copy(id = "sink-from-code") else it },
            wires = built.wires.map { if (it.consumer == added) it.copy(consumer = "sink-from-code") else it }).recounted()
        assertTrue(live.applied(part))
        val replay = live.replay(part)
        assertTrue(replay.conflicts.isEmpty())
        val reopened = GraphEditor(live, placement, "draft-1", "Wiring", 1, replay.shape, conflicts = replay.conflicts)
        assertTrue(reopened.diff.empty)
        assertTrue(reopened.applied)
    }

    @Test
    fun changesThatNoLongerFitBecomeConflictsInsteadOfComingBack() {
        val replay = shape.without(setOf("b")).replay(drafted())
        assertEquals(listOf("changeNode", "connect"), replay.conflicts.map { it.op })
        assertNull(replay.shape.node("b"))
        assertEquals(1, replay.shape.nodes.count { it.label == "Sink" })
    }

    @Test
    fun aDraftKeepsWhatChangedInTheLiveGraphSinceItWasSaved() {
        val editor = GraphEditor(shape, placement)
        editor.update("b") { it.copy(ports = it.ports + PortShape("extra", "Int", false, PortWiring.Unmatched)) }
        val live = shape.copy(nodes = shape.nodes.map { if (it.id == "b") it.copy(label = "Bee", ports = listOf(PortShape("live", "Int", false, PortWiring.Unmatched)) + it.ports) else it })
        val merged = live.replay(editor.graphPart()).shape.node("b")!!
        assertEquals("Bee", merged.label)
        assertEquals(listOf("live", "in", "extra"), merged.ports.map { it.key })
    }

    @Test
    fun stepsAreNamedAndHistoryMovesSeveralAtOnce() {
        val editor = GraphEditor(shape, placement)
        editor.rename("a", "Alpha")
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.comment(Selection(setOf("c")))
        assertEquals(listOf("Add a comment", "Connect Alpha.out → B.in (Int)", "Change A: renamed from A"), editor.history)
        editor.undo(2)
        assertEquals(listOf("Change A: renamed from A"), editor.history)
        assertEquals(listOf("Connect Alpha.out → B.in (Int)", "Add a comment"), editor.future)
        editor.redo(2)
        assertTrue(editor.future.isEmpty())
        assertEquals(1, editor.shape.wires.size)
        editor.setting("b", "retries", "3")
        assertEquals("Change B: + setting retries = 3", editor.history.first())
    }

    @Test
    fun unsavedEditsComeBackAfterTheEditorCloses() {
        val editor = GraphEditor(shape, placement, id = "draft-9", title = "Lost work")
        editor.rename("a", "Alpha")
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        val kept = ChangeSet.decode(editor.recovery(1_000L).encode())
        val next = GraphEditor(shape, placement)
        next.recovered = kept
        next.restore(kept)
        assertEquals(editor.shape, next.shape)
        assertEquals("draft-9" to "Lost work", next.id to next.title)
        assertNull(next.recovered)
        assertEquals(listOf("Restore unsaved edits"), next.history)
        next.undo()
        assertTrue(next.diff.empty)
    }

    @Test
    fun copiedNodesTravelAsChangeSetText() {
        val board = object : SystemClipboard {
            var text: String? = null
            override fun write(text: String) { this.text = text }
            override fun read(then: (String?) -> Unit) = then(text)
        }
        val editor = GraphEditor(shape, placement).apply { system = board }
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        assertEquals(2, editor.copy(Selection(setOf("a", "b"))))
        val set = ChangeSet.decode(board.text!!)
        assertEquals(ChangeSet.Format, set.format)
        assertEquals(listOf("addNode", "addNode", "connect"), set.graph!!.changes.map { it.op })
        val other = GraphEditor(shape, placement).apply { system = board }
        other.paste(Spot(800.0, 800.0))
        assertEquals(setOf("A 2", "B 2"), other.selection.nodes.mapTo(hashSetOf()) { other.node(it)!!.label })
        assertEquals(1, other.shape.wires.count { it.provider in other.selection.nodes && it.consumer in other.selection.nodes })
        assertFalse(other.paste(Spot(0.0, 0.0), "not a change set"))
    }

    @Test
    fun aSelectionSavedAsATemplateComesBackFromThePalette() {
        var kept: ChangeSet? = null
        val editor = GraphEditor(shape, placement).apply { onTemplate = { kept = it } }
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.select(Selection(setOf("a", "b")))
        editor.template("Pair")
        assertEquals("Pair", kept!!.title)
        val template = editor.choices.first()
        assertEquals(SavedCategory to "Pair", template.category to template.label)
        editor.add(template, Spot(900.0, 900.0))
        assertEquals(5, editor.shape.nodes.size)
        assertEquals(2, editor.shape.wires.size)
    }

    @Test
    fun groupingFoldingAndOpeningASubgraph() {
        val editor = GraphEditor(shape, placement)
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.group("Sources", Selection(setOf("a", "c")))
        val note = editor.selection.notes.single()
        assertEquals(ScopeShape(note, "Sources", "app"), editor.shape.scopes.single { it.id == note })
        assertEquals(setOf(note), editor.shape.nodes.filter { it.id != "b" }.mapTo(hashSetOf()) { it.scope })
        assertTrue(editor.graphPart().changes.any { it.op == "addGraph" })
        editor.fold(note)
        assertEquals(setOf(note, "b"), editor.layout.cards.keys)
        assertEquals(setOf("a", "c"), editor.folds.getValue(note).members)
        val card = editor.layout.cards.getValue(note)
        val pin = card.pins.single()
        assertEquals("a${FoldSeparator}out", pin.key)
        assertEquals(PinEnd("a", "out", true), editor.pinAt(Spot(card.x + card.width - 4, card.y + pin.y)))
        assertEquals(listOf("$note.a${FoldSeparator}out>b.in"), editor.layout.links.map { it.id })
        assertTrue(editor.anchor(PinEnd("a", "out", true)) != null)
        val x = (card.x + card.width / 2).toFloat()
        val y = (card.y + pin.y).toFloat()
        assertTrue(editor.press(x, y, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.release(x, y, Offset.Zero)
        assertEquals(setOf(note, "b"), editor.layout.cards.keys)
        assertTrue(editor.press(x, y, Offset.Zero, 4f, PointerKeyboardModifiers(), 2, false))
        assertEquals(setOf("a", "b", "c"), editor.layout.cards.keys)
        editor.open(note)
        assertEquals(setOf("a", "c"), editor.layout.cards.keys)
        assertEquals(listOf(note), editor.crumbs.map { it.id })
        editor.invoke(CommandId("edit-escape"))
        assertNull(editor.focus)
        assertEquals(setOf("a", "b", "c"), editor.layout.cards.keys)
    }

    @Test
    fun aWiderInputAcceptsANarrowerOutput() {
        val numbers = object : GraphTerms { override fun fits(provided: String, needed: String) = provided == needed || provided == "Int" && needed == "Number" }
        val wide = shape.copy(nodes = shape.nodes + NodeShape("d", "app", "D", "D", NodeKind.Service, ports = listOf(PortShape("in", "Number", false, PortWiring.Unmatched))))
        val editor = GraphEditor(wide, placement, terms = numbers)
        assertEquals("Connect out to in · Int fits Number", editor.connect(PinEnd("a", "out", true), PinEnd("d", "in", false)).message)
        assertTrue(editor.valid)
        assertFalse(GraphEditor(wide, placement).connect(PinEnd("a", "out", true), PinEnd("d", "in", false)).ok)
        assertTrue(Template("Gauge", NodeKind.Service, "Gauge", listOf(PortShape("in", "Number", false, PortWiring.Unmatched))).offers(wide.node("a")!!.port("out", true), numbers::fits))
    }

    @Test
    fun tidyLaysTheSelectionOutInDependencyOrder() {
        val editor = GraphEditor(shape, Placement(mapOf("a" to Spot(800.0, 300.0), "b" to Spot(0.0, 0.0), "c" to Spot(48.0, 600.0))))
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.selectAll()
        editor.invoke(CommandId("edit-tidy"))
        val (a, b, c) = listOf("a", "b", "c").map(editor.placement.cards::getValue)
        assertEquals(a.x, c.x)
        assertTrue(a.x < b.x && a.y < c.y && b.y == 0.0)
        assertEquals("Tidy up 3 cards", editor.history.first())
    }

    @Test
    fun newNodesLandInAFreeSpotInsideTheirArea() {
        val zoned = shape.copy(scopes = shape.scopes + ScopeShape("zone", "Zone", "app"))
        val editor = GraphEditor(zoned, placement.copy(notes = listOf(Note("zone", "Zone", 600.0, -100.0, 400.0, 300.0))))
        val onTop = editor.add(sink, Spot(136.0, 23.0))
        val card = editor.layout.cards.getValue(onTop)
        assertTrue(editor.layout.cards.values.filter { it.id != onTop }.none { it.x < card.x + card.width && card.x < it.x + it.width && it.y < card.y + card.height && card.y < it.y + it.height })
        val inside = editor.add(sink, Spot(800.0, 190.0))
        assertEquals("zone", editor.node(inside)!!.scope)
        val zone = editor.placement.notes.single()
        val box = editor.layout.cards.getValue(inside)
        assertTrue(zone.y + zone.height >= box.y + box.height && zone.x + zone.width >= box.x + box.width)
    }

    @Test
    fun atlasRowsKeyedByTitleSurviveRenumberingAndNewCardsStayInTheirArea() {
        fun source(vararg chapters: String) = SurfaceGraphNode("source:a", "Calculus", "Book", "area:maths", rows = chapters.mapIndexed { i, title -> SurfaceGraphRow("unit:a:$i", title, i.toString()) })
        val byTitle: (SurfaceGraphRow) -> String = { it.title }
        val groups = listOf(SurfaceGraphGroup("area:maths", "Maths"))
        val drafted = SurfaceGraphDocument(listOf(source("Limits", "Series")), emptyList(), groups, rowKey = byTitle)
        val layout = BlueprintLayout(Unit, linkedMapOf("source:a" to Card("source:a", 0.0, 0.0, 272.0, BlueprintEngine.cardHeight(2), emptyList(), 0)),
            listOf(Frame("area:maths", "Maths", -18.0, -40.0, 308.0, 160.0, linkedSetOf("source:a"), emptyList(), false)), emptyList(), 300.0, 120.0)
        val editor = drafted.editor(layout, EditorInk(), "Atlas")
        assertEquals(listOf("Limits", "Series"), editor.terms.rows(editor.node("source:a")!!))
        editor.update("source:a") { node -> node.copy(ports = node.ports.filterNot { it.key == "Series" }) }
        val live = SurfaceGraphDocument(listOf(source("Intro", "Limits", "Series")), emptyList(), groups, rowKey = byTitle)
        assertEquals(listOf("Intro", "Limits"), live.rows(live.shape.replay(editor.graphPart()).shape.node("source:a")!!))
        val twice = SurfaceGraphDocument(listOf(source("Exercises", "Exercises")), emptyList(), groups, rowKey = byTitle)
        assertEquals(listOf("Exercises", "Exercises (2)"), twice.rows(twice.shape.node("source:a")!!))
        val added = editor.add(drafted.templates.first(), Spot(150.0, 110.0))
        assertEquals("area:maths", editor.node(added)!!.scope)
        val area = editor.layout.frames.single()
        val card = editor.layout.cards.getValue(added)
        assertTrue(added in area.nodes && area.y + area.height >= card.y + card.height)
        assertEquals("book", drafted.templates.first().category)
        assertFalse(editor.terms.subgraphs)
    }
}
