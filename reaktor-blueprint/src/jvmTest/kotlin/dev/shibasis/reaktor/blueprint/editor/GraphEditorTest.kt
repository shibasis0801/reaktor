package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphDocument
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphGroup
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphLink
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphEditorTest {
    private val shape = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        NodeShape("a", "app", "A", "A", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused), PortShape("text", "String", true, PortWiring.Unused))),
        NodeShape("b", "app", "B", "B", NodeKind.Service, ports = listOf(PortShape("in", "Int", false, PortWiring.Unmatched))),
        NodeShape("c", "app", "C", "C", NodeKind.Service, ports = listOf(PortShape("out", "Int", true, PortWiring.Unused))),
    ), emptyList(), emptyList())

    private val placement = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0), "c" to Spot(0.0, 200.0)))

    @Test
    fun wiresFollowTheConnectRules() {
        assertEquals("Type mismatch: needs Int, provides String", shape.verdict("a", "text", "b", "in").message)
        assertEquals("Drag from an output to an input", shape.verdict("b", "in", "a", "out").message)
        val one = shape.connect(shape.wire("a", "out", "b", "in")!!)
        assertEquals("Replace the link from A", one.verdict("c", "out", "b", "in").message)
        assertEquals("Already connected", one.verdict("a", "out", "b", "in").message)
        assertEquals(listOf("c"), one.connect(one.wire("c", "out", "b", "in")!!).wires.map { it.provider })
        val many = one.connect(one.wire("c", "out", "b", "in", single = false)!!, single = false)
        assertEquals(setOf("a", "c"), many.wires.mapTo(hashSetOf()) { it.provider })
        assertTrue(many.problems(single = false).isEmpty())
        assertEquals(1, many.problems().size)
        assertEquals(PortWiring.Connected, many.node("b")!!.port("in", false)!!.wiring)
    }

    @Test
    fun editsShowAsDiffsAndUndo() {
        val editor = GraphEditor(shape, placement)
        editor.rename("a", "Alpha")
        assertEquals(Change.Changed, editor.diff.nodes["a"])
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        assertFalse(editor.connect(PinEnd("a", "text", true), PinEnd("b", "in", false)).ok)
        assertEquals(listOf("a.out>b.in"), editor.shape.wires.map { it.id })
        assertEquals(Change.Added, editor.diff.wires["a.out>b.in"])
        editor.delete(Selection(setOf("c")))
        assertEquals(Change.Removed, editor.diff.nodes["c"])
        assertEquals(Change.entries.toSet(), editor.diff.differences.mapTo(hashSetOf()) { it.change })
        editor.undo(); editor.undo()
        assertTrue(editor.shape.wires.isEmpty())
        editor.redo()
        assertEquals(1, editor.shape.wires.size)
        assertTrue(editor.dirty && editor.valid)
    }

    @Test
    fun changeSetsKeepWhatMovedAndReopenOnTheLiveGraph() {
        val editor = GraphEditor(shape, placement, id = "draft-1", title = "Wiring")
        editor.rename("b", "Beta")
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.bend("a.out>b.in", Spot(200.0, 40.0))
        assertTrue(editor.press(10f, 210f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag(130f, 330f, Offset.Zero)
        editor.release(130f, 330f, Offset.Zero)
        val draft = editor.draft(42_000L)
        val set = editor.changeSet(draft, AppRef("Test"))
        assertEquals(set, ChangeSet.decode(set.encode()))
        assertEquals(ChangeSet.Format, set.format)
        assertEquals(42_000L, set.savedAtMillis)
        val part = set.graph!!
        assertEquals(setOf("c"), part.layout!!.cards.keys)
        assertEquals(setOf("a.out>b.in"), part.layout!!.bends.keys)

        val reopened = GraphEditor(shape, placement.with(part.layout!!), set.id, set.title, set.revision, shape.replay(part).shape, origin = placement)
        assertEquals("Beta", reopened.node("b")!!.label)
        assertEquals(editor.diff.nodes, reopened.diff.nodes)
        assertEquals(editor.diff.wires, reopened.diff.wires)
        assertEquals(editor.placement.cards["c"], reopened.placement.cards["c"])
        assertEquals(part, reopened.graphPart())
        editor.saved(draft)
        assertFalse(editor.dirty)
        assertEquals(1, editor.revision)
    }

    @Test
    fun findJumpsToNodesPortsAndComments() {
        val editor = GraphEditor(shape, placement)
        assertEquals(listOf("b"), editor.find("b").map { it.id })
        assertEquals("text", editor.find("text").single { it.id == "a" }.detail)
        editor.comment(Selection(setOf("a", "c")))
        editor.rename(editor.selection.notes.single(), "Inputs")
        assertEquals("comment", editor.find("inputs").single().detail)
        assertTrue(editor.find("  ").isEmpty())
        editor.reveal("b")
        assertEquals(setOf("b"), editor.selection.nodes)
        assertEquals(listOf(editor.layout.cards.getValue("b")), editor.aim!!.boxes)
        editor.select(Selection(setOf("a", "c")))
        assertEquals(setOf("a", "c"), editor.framed().map { (it as Card).id }.toSet())
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.reveal("a.out>b.in")
        assertEquals(setOf("a", "b"), editor.aim!!.boxes.map { (it as Card).id }.toSet())
    }

    @Test
    fun unwiredInputsWarnWithoutBlockingSave() {
        val editor = GraphEditor(shape, placement)
        assertTrue(editor.warnings.isEmpty())
        val sink = editor.add(Template("Sink", NodeKind.Service, "Sink", listOf(PortShape("in", "Int", false, PortWiring.Unmatched), PortShape("bus", "Bus", false, PortWiring.Injected))), Spot(0.0, 600.0))
        assertEquals(listOf("Nothing provides Int to Sink.in"), editor.warnings.map { it.text })
        assertEquals(sink to "in", editor.warnings.single().let { it.subject to it.port })
        assertTrue(editor.valid)
        assertEquals("Service:Sink:Sink", GraphEditor.recent.first())
        assertTrue(editor.connect(PinEnd("c", "out", true), PinEnd(sink, "in", false)).ok)
        assertTrue(editor.warnings.isEmpty())
        val atlas = GraphEditor(shape, placement, single = false)
        atlas.add(Template("Sink", NodeKind.Service, "Sink", listOf(PortShape("in", "Int", false, PortWiring.Unmatched))), Spot(0.0, 600.0))
        assertTrue(atlas.warnings.isEmpty())
    }

    @Test
    fun inputsTheAppSatisfiesWithoutAWireStaySatisfied() {
        val bound = shape.copy(nodes = shape.nodes.map { if (it.id == "b") it.copy(ports = listOf(PortShape("in", "Int", false, PortWiring.Connected, connections = 1))) else it })
        val editor = GraphEditor(bound, placement)
        assertFalse(editor.dirty)
        editor.rename("a", "Alpha")
        assertEquals(PortWiring.Injected, editor.node("b")!!.port("in", false)!!.wiring)
        assertTrue(editor.warnings.isEmpty())
        assertEquals(setOf("a"), editor.diff.nodes.keys)
    }

    @Test
    fun aDraftRecountedBeforeItOpensKeepsWhatTheBaseSatisfiesWithoutAWire() {
        val bound = shape.copy(nodes = shape.nodes.map { if (it.id == "b") it.copy(ports = listOf(PortShape("in", "Int", false, PortWiring.Connected, connections = 1))) else it })
        val replayed = bound.copy(nodes = bound.nodes.map { if (it.id == "a") it.copy(label = "Alpha") else it }).recounted()
        assertEquals(PortWiring.Unmatched, replayed.node("b")!!.port("in", false)!!.wiring)
        val resumed = GraphEditor(bound, placement, shape = replayed)
        assertEquals(PortWiring.Injected, resumed.node("b")!!.port("in", false)!!.wiring)
        assertTrue(resumed.warnings.isEmpty())
        assertEquals(setOf("a"), resumed.diff.nodes.keys)

        val linked = GraphEditor(shape, placement).apply { assertTrue(connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok) }.shape
        val cut = GraphEditor(linked, placement, shape = linked.copy(wires = emptyList()).recounted())
        assertEquals(listOf("in"), cut.warnings.map { it.port })
    }

    @Test
    fun duplicatingLeavesTheClipboardAlone() {
        val editor = GraphEditor(shape, placement)
        assertEquals(1, editor.copy(Selection(setOf("a"))))
        editor.select(Selection(setOf("b")))
        editor.duplicate()
        val twin = editor.selection.nodes.single()
        assertEquals("B 2", editor.node(twin)!!.label)
        assertEquals(Spot(432.0, 32.0), editor.placement.cards[twin])
        editor.paste(Spot(800.0, 400.0))
        assertEquals(listOf("A 2"), editor.selection.nodes.map { editor.node(it)!!.label })
    }

    @Test
    fun renamingAPortCarriesItsWiresAndBends() {
        val editor = GraphEditor(shape, placement)
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.bend("a.out>b.in", Spot(200.0, 40.0))
        editor.rename("b", editor.node("b")!!.port("in", false)!!, "value")
        assertEquals(listOf("a.out>b.value"), editor.shape.wires.map { it.id })
        assertEquals(setOf("a.out>b.value"), editor.placement.bends.keys)
        assertEquals(PortWiring.Connected, editor.node("b")!!.port("value", false)!!.wiring)
        assertTrue(editor.valid)
        editor.rename("a", editor.node("a")!!.port("out", true)!!, "text")
        assertEquals("A already has an output named text", editor.notice)
        editor.undo()
        assertEquals(listOf("a.out>b.in"), editor.shape.wires.map { it.id })
    }

    @Test
    fun escapeCancelsADragAndLeavesNoHalfMove() {
        val editor = GraphEditor(shape, placement)
        assertTrue(editor.press(10f, 10f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag(120f, 90f, Offset.Zero)
        assertEquals(Spot(112.0, 80.0), editor.placement.cards["a"])
        editor.invoke(CommandId("edit-escape"))
        editor.release(120f, 90f, Offset.Zero)
        assertEquals(Spot(0.0, 0.0), editor.placement.cards["a"])
        assertFalse(editor.canUndo)
    }

    @Test
    fun droppingACardIntoAnAreaMovesItThere() {
        val zoned = shape.copy(scopes = shape.scopes + ScopeShape("zone", "Zone", "app"))
        val editor = GraphEditor(zoned, placement.copy(notes = listOf(Note("zone", "Zone", 600.0, -100.0, 600.0, 600.0))))
        assertTrue(editor.press(10f, 210f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag(710f, 110f, Offset.Zero)
        editor.release(710f, 110f, Offset.Zero)
        assertEquals("zone", editor.node("c")!!.scope)
        assertEquals("Moved C into Zone", editor.notice)
        assertEquals("moved to another graph", editor.diff.differences.single { it.subject == "c" }.detail)
        assertTrue(editor.press(714f, 106f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag(714f, 1306f, Offset.Zero)
        editor.release(714f, 1306f, Offset.Zero)
        assertEquals("zone", editor.node("c")!!.scope)
        editor.undo(); editor.undo()
        assertEquals("app", editor.node("c")!!.scope)
        assertEquals(Spot(0.0, 200.0), editor.placement.cards["c"])
        editor.rename("zone", "Rooms")
        assertEquals("Rooms", editor.shape.scopes.single { it.id == "zone" }.label)
        assertEquals("renamed from Zone", editor.diff.differences.single { it.subject == "zone" }.detail)
    }

    @Test
    fun selectLinkedWalksDependenciesBothWays() {
        val editor = GraphEditor(shape, placement)
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        editor.select(Selection(setOf("b")))
        editor.selectLinked(upstream = true)
        assertEquals(setOf("a", "b"), editor.selection.nodes)
        editor.select(Selection(setOf("c")))
        editor.selectLinked(upstream = false)
        assertEquals(setOf("c"), editor.selection.nodes)
        editor.select(Selection(setOf("a")))
        editor.selectLinked(upstream = false)
        assertEquals(setOf("a", "b"), editor.selection.nodes)
    }

    @Test
    fun atlasScenesRoundTripThroughTheShape() {
        val nodes = listOf(
            SurfaceGraphNode("source:a", "Calculus", "Book", "area:maths", rows = listOf(SurfaceGraphRow("unit:a:0", "Limits", "0"), SurfaceGraphRow("unit:a:1", "Series", "1")), symbol = "book"),
            SurfaceGraphNode("source:b", "Analysis", "Course", "area:maths", rows = listOf(SurfaceGraphRow("unit:b:0", "Sequences", "0")), symbol = "course"),
        )
        val links = listOf(SurfaceGraphLink("c:1", "unit:a:0", "unit:b:0", "builds on"), SurfaceGraphLink("r:2:related", "source:a", "source:b"))
        val groups = listOf(SurfaceGraphGroup("area:maths", "Maths"))
        val document = SurfaceGraphDocument(nodes, links, groups, linkFamily = { if (it.id.startsWith("c:")) "chapter" else "related" })
        assertEquals(2, document.shape.wires.size)
        val layout = BlueprintLayout(Unit, linkedMapOf(
            "source:a" to Card("source:a", 0.0, 0.0, 272.0, BlueprintEngine.cardHeight(2), emptyList(), 0),
            "source:b" to Card("source:b", 400.0, 0.0, 272.0, BlueprintEngine.cardHeight(1), emptyList(), 0),
        ), listOf(Frame("area:maths", "Maths", -20.0, -40.0, 720.0, 200.0, linkedSetOf("source:a", "source:b"), emptyList(), false)), emptyList(), 720.0, 200.0)
        val editor = document.editor(layout, EditorInk(), "Atlas")
        val scene = document.scene(editor)
        assertSame(nodes[0], scene.nodes.first { it.id == "source:a" })
        assertEquals(setOf("unit:a:0" to "unit:b:0", "source:a" to "source:b"), scene.links.mapTo(hashSetOf()) { it.from to it.to })
        assertEquals("chapter", editor.layout.links.first { it.id == "source:a.0>source:b.0" }.family)
        assertEquals(BlueprintEngine.cardHeight(2), editor.layout.cards.getValue("source:a").height)
        assertTrue(editor.connect(PinEnd("source:a", "1", true), PinEnd("source:b", "0", false)).ok)
        assertEquals(2, editor.shape.wires.count { it.consumer == "source:b" && it.consumerPort == "0" })
        editor.rename("source:b", "Real analysis")
        val edited = document.scene(editor)
        assertEquals("Real analysis", edited.nodes.first { it.id == "source:b" }.title)
        assertTrue(edited.links.any { it.from == "unit:a:1" && it.to == "unit:b:0" })
        assertTrue(editor.valid)
        assertEquals("Limits", editor.terms.port(editor.node("source:a")!!, "0"))
        assertEquals("Real analysis · Sequences", editor.terms.end(editor.node("source:b")!!, "0"))
        editor.update("source:a") { editor.terms.row(it, "Continuity") }
        assertEquals(listOf("0", "1", "Continuity"), editor.terms.rows(editor.node("source:a")!!))
        assertTrue(editor.diff.differences.any { it.subject == "source:a" && "+ row Continuity" in it.detail })
        assertEquals(listOf("Limits", "Series", "Continuity"), document.scene(editor).nodes.first { it.id == "source:a" }.rows.map { it.title })
    }
}
