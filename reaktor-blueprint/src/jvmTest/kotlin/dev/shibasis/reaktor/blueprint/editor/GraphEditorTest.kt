package dev.shibasis.reaktor.blueprint.editor

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
    fun draftsRoundTripAsJson() {
        val editor = GraphEditor(shape, placement, id = "draft-1", title = "Wiring")
        editor.rename("b", "Beta")
        val draft = editor.draft(42L)
        assertEquals(draft, GraphDraft.decode(draft.encode()))
        val reopened = GraphEditor.open(draft)
        assertEquals("Beta", reopened.node("b")!!.label)
        assertEquals(Change.Changed, reopened.diff.nodes["b"])
        editor.saved(draft)
        assertFalse(editor.dirty)
        assertEquals(1, editor.revision)
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
