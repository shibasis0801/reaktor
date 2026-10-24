package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.CanvasCamera
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphEditorNextTest {
    private fun node(id: String, vararg ports: PortShape, type: String = id.uppercase(), scope: String = "app") = NodeShape(id, scope, id.uppercase(), type, NodeKind.Service, ports = ports.toList())
    private fun out(key: String, type: String = "Int") = PortShape(key, type, true, PortWiring.Unused)
    private fun into(key: String, type: String = "Int") = PortShape(key, type, false, PortWiring.Unmatched)
    private val chain = GraphShape(listOf(ScopeShape("app", "App")), listOf(
        node("a", out("out"), into("back")),
        node("b", into("in"), out("out")),
        node("c", into("in")),
    ), emptyList(), emptyList())
    private val spots = Placement(mapOf("a" to Spot(0.0, 0.0), "b" to Spot(400.0, 0.0), "c" to Spot(400.0, 200.0)))

    private class Camera(override val seen: Area? = Area(0.0, 0.0, 800.0, 600.0)) : CanvasCamera {
        val framed = mutableListOf<Bounds>()
        val revealed = mutableListOf<Bounds>()
        override val zoom = 1.0
        override fun look(x: Double, y: Double) {}
        override fun reveal(box: Bounds) { revealed += box }
        override fun frame(box: Bounds) { framed += box }
        override fun world(window: Offset): Offset? = window - Offset(100f, 50f)
    }

    @Test
    fun aNewCycleWarnsWithItsPathButOneAlreadyInTheAppDoesNot() {
        val editor = GraphEditor(chain, spots)
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false)).ok)
        assertTrue(editor.warnings.none { it.path.isNotEmpty() })
        assertTrue(editor.connect(PinEnd("b", "out", true), PinEnd("a", "back", false)).ok)
        val cycle = editor.warnings.single { it.path.isNotEmpty() }
        assertEquals("A → B → A form a cycle", cycle.text)
        assertEquals(2, cycle.path.size)
        assertTrue(editor.valid)
        val known = GraphEditor(editor.shape, spots)
        assertTrue(known.warnings.none { it.path.isNotEmpty() })
    }

    @Test
    fun aConversionIsNamedInTheVerdictAndListedForTheWire() {
        val terms = object : GraphTerms {
            override fun fits(provided: String, needed: String) = provided == needed || provided == "Int" && needed == "Number"
            override fun conversion(provided: String, needed: String) = if (provided == "Int" && needed == "Number") "Int is a Number" else null
        }
        val shape = chain.copy(nodes = chain.nodes + node("n", into("value", "Number")))
        val editor = GraphEditor(shape, spots, terms = terms)
        val verdict = editor.verdict(PinEnd("a", "out", true), PinEnd("n", "value", false))
        assertTrue(verdict.ok)
        assertTrue(verdict.message.endsWith("· Int is a Number"), verdict.message)
        editor.connect(PinEnd("a", "out", true), PinEnd("n", "value", false))
        assertEquals(listOf("Int is a Number"), editor.converted.map { it.second })
    }

    @Test
    fun aMismatchWithAnAdapterInsertsTheAdapterBetweenThem() {
        val adapter = Template("To text", NodeKind.Service, "ToText", listOf(into("value", "Int"), out("text", "String")))
        val shape = chain.copy(nodes = chain.nodes + node("t", into("label", "String")))
        val editor = GraphEditor(shape, spots + mapOf("t" to Spot(900.0, 0.0)), catalogue = { listOf(adapter) })
        val verdict = editor.verdict(PinEnd("a", "out", true), PinEnd("t", "label", false))
        assertTrue(verdict.ok)
        assertTrue(verdict.message.startsWith("Convert through To text"), verdict.message)
        editor.connect(PinEnd("a", "out", true), PinEnd("t", "label", false))
        val inserted = editor.shape.nodes.single { it.type == "ToText" }
        assertTrue(editor.shape.wires.any { it.provider == "a" && it.consumer == inserted.id })
        assertTrue(editor.shape.wires.any { it.provider == inserted.id && it.consumer == "t" && it.consumerPort == "label" })
        assertEquals("Convert through To text", editor.history.first())
    }

    @Test
    fun arrowsWalkAlongWiresAndFallBackToTheNearestCard() {
        val editor = GraphEditor(chain, spots)
        editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false))
        editor.connect(PinEnd("a", "out", true), PinEnd("c", "in", false))
        assertFalse(editor.travel(1, 0))
        editor.select(Selection(setOf("a")))
        assertTrue(editor.travel(1, 0))
        assertEquals(setOf("b"), editor.selection.nodes)
        assertTrue(editor.travel(0, 1))
        assertEquals(setOf("c"), editor.selection.nodes)
        assertTrue(editor.travel(-1, 0))
        assertEquals(setOf("a"), editor.selection.nodes)
    }

    @Test
    fun findReachesIntoFoldsAndRevealingAHitOpensTheFold() {
        val editor = GraphEditor(chain, spots)
        editor.group("Pair", Selection(setOf("b", "c")))
        val note = editor.selection.notes.single()
        editor.fold(note)
        assertFalse("b" in editor.layout.cards)
        val hit = editor.find("B").single { it.id == "b" }
        assertTrue(hit.detail.contains("inside Pair"), hit.detail)
        editor.reveal("b")
        assertTrue("b" in editor.layout.cards)
        assertEquals(setOf("b"), editor.selection.nodes)
    }

    @Test
    fun bookmarksRememberAViewAndComeBackToIt() {
        val editor = GraphEditor(chain, spots)
        val camera = Camera()
        editor.attach(camera)
        editor.invoke(CommandId("edit-mark-3"))
        assertEquals(Mark(0.0, 0.0, 800.0, 600.0), editor.placement.marks[3])
        editor.invoke(CommandId("edit-go-3"))
        assertEquals(listOf<Bounds>(Area(0.0, 0.0, 800.0, 600.0)), camera.framed)
        editor.invoke(CommandId("edit-go-4"))
        assertEquals("No bookmark 4 yet · ⌃4 sets one", editor.notice)
        assertEquals(Mark(0.0, 0.0, 800.0, 600.0), editor.graphPart().layout?.marks?.get(3))
    }

    @Test
    fun aNoteOnANodeTravelsWithTheDraftAndUndoes() {
        val editor = GraphEditor(chain, spots)
        editor.remark("b", "  Make this async  ")
        assertEquals("Make this async", editor.placement.remarks["b"])
        assertEquals("Note on B", editor.history.first())
        assertEquals("Make this async", editor.graphPart().layout?.remarks?.get("b"))
        editor.undo()
        assertNull(editor.placement.remarks["b"])
        editor.redo()
        editor.remark("b", "")
        assertNull(editor.placement.remarks["b"])
    }

    @Test
    fun aSharedKnotCarriesEveryLinkFromAPinAndMovesAsOne() {
        val editor = GraphEditor(chain, spots)
        editor.connect(PinEnd("a", "out", true), PinEnd("b", "in", false))
        editor.connect(PinEnd("a", "out", true), PinEnd("c", "in", false))
        editor.bundle(PinEnd("a", "out", true))
        val knots = editor.placement.bends.values.map { it.first() }
        assertEquals(2, knots.size)
        assertEquals(1, knots.toSet().size)
        val knot = knots.first()
        assertTrue(editor.press(knot.x.toFloat(), knot.y.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false))
        editor.drag((knot.x + 64).toFloat(), (knot.y + 32).toFloat(), Offset.Zero)
        editor.release((knot.x + 64).toFloat(), (knot.y + 32).toFloat(), Offset.Zero)
        assertEquals(setOf(Spot(knot.x + 64, knot.y + 32)), editor.placement.bends.values.map { it.first() }.toSet())
        val moved = Spot(knot.x + 64, knot.y + 32)
        assertTrue(editor.press(moved.x.toFloat(), moved.y.toFloat(), Offset.Zero, 4f, PointerKeyboardModifiers(), 2, false))
        assertTrue(editor.placement.bends.isEmpty())
    }

    @Test
    fun comparingWithAVersionShowsWhatChangedSinceAndRestoringItUndoes() {
        val editor = GraphEditor(chain, spots)
        editor.rename("b", "Beta")
        val first = editor.changeSet(editor.draft(1000)).copy(revision = 1)
        editor.saved(editor.draft(1000))
        editor.rename("c", "Gamma")
        assertEquals(setOf("b", "c"), editor.diff.nodes.keys)
        editor.compare(first)
        assertEquals(setOf("c"), editor.diff.nodes.keys)
        editor.compare(first)
        assertNull(editor.against)
        editor.rollback(first)
        assertEquals("C", editor.shape.node("c")?.label)
        assertEquals("Beta", editor.shape.node("b")?.label)
        assertEquals("Restore revision 1", editor.history.first())
    }

    @Test
    fun someoneElseEditingMakesTheDraftReadOnlyUntilTakeOver() {
        val editor = GraphEditor(chain, spots, id = "draft-1", revision = 1)
        var took = 0
        var asked = 0
        editor.onTakeOver = { took++ }
        editor.onLocked = { asked++ }
        editor.others = listOf(Holder("Kedarnath", 0, editing = true))
        editor.rename("b", "Beta")
        assertEquals("B", editor.shape.node("b")?.label)
        assertEquals("Kedarnath is editing this draft · take over to change it", editor.notice)
        assertEquals(1, asked)
        editor.press(10f, 10f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
        assertNull(editor.gesture)
        editor.takeOver()
        assertEquals(1, took)
        assertFalse(editor.locked)
        editor.rename("b", "Beta")
        assertEquals("Beta", editor.shape.node("b")?.label)
        editor.others = listOf(Holder("Kedarnath", 5, editing = true))
        assertTrue(editor.locked)
        editor.others = listOf(Holder("Kedarnath", 5, editing = true, ahead = false))
        assertFalse(editor.locked)
        editor.others = listOf(Holder("Kedarnath", 5, editing = false))
        assertFalse(editor.locked)
    }

    @Test
    fun aLinkedPartUpdatesEveryOlderUseAsOneStep() {
        val kept = mutableListOf<ChangeSet>()
        val editor = GraphEditor(chain, spots)
        editor.onTemplate = { kept += it }
        editor.connect(PinEnd("b", "out", true), PinEnd("c", "in", false))
        editor.select(Selection(setOf("b", "c")))
        editor.template("Pair")
        val part = kept.single()
        assertEquals(listOf("b${FoldSeparator}in"), part.graph!!.signature().map { it.key })
        val choice = editor.choices.single { it.part != null }
        val first = editor.add(choice, Spot(0.0, 600.0))
        val use = editor.node(first)!!.attributes.getValue(PartUseKey)
        editor.add(choice, Spot(0.0, 900.0))
        assertEquals(2, editor.shape.uses(part.id).size)
        assertTrue(editor.connect(PinEnd("a", "out", true), PinEnd(editor.shape.nodes.first { it.attributes[PartUseKey] == use && it.label.startsWith("B") }.id, "in", false)).ok)
        val inner = editor.shape.nodes.first { it.attributes[PartUseKey] == use && it.label.startsWith("C") }
        editor.update(inner.id) { it.copy(ports = it.ports + into("extra")) }
        editor.select(Selection(editor.shape.nodes.filter { it.attributes[PartUseKey] == use }.mapTo(hashSetOf()) { it.id }))
        editor.keepPart(inner.id)
        assertEquals(2, kept.last().revision)
        assertEquals(part.id, kept.last().id)
        val stale = editor.stale(part.id)
        assertEquals(1, stale.size)
        val before = stale.single().nodes.toSet()
        editor.updateUses(part.id)
        assertTrue(editor.stale(part.id).isEmpty())
        val updated = editor.shape.nodes.filter { it.id in before }
        assertEquals(2, updated.size)
        assertTrue(updated.any { node -> node.ports.any { it.key == "extra" } })
        assertTrue(editor.shape.wires.any { it.provider == "a" && it.consumer in editor.shape.nodes.filter { n -> n.attributes[PartUseKey] == use }.map { n -> n.id } })
        assertTrue(editor.history.first().startsWith("Update 1 use of Pair"))
    }

    @Test
    fun keysCanBeRemappedAndRoundTrip() {
        val chord = KeyStroke(KeyName.K, meta = true, shift = true).chord(KeyConvention.Mac)
        assertEquals(Chord.Of(KeyName.K, primary = true, shift = true), chord)
        assertEquals("primary+shift+K", chord.text())
        assertEquals(chord, chordOf(chord.text()))
        val editor = GraphEditor(chain, spots)
        val saved = mutableListOf<EditorPrefs>()
        editor.onPrefs = { saved += it }
        editor.prefer(editor.prefs.copy(keys = mapOf("edit-comment" to chord.text(), "edit-straighten" to "")))
        assertEquals(chord, editor.commands.commands.single { it.id.value == "edit-comment" }.chord)
        assertNull(editor.commands.commands.single { it.id.value == "edit-straighten" }.chord)
        assertEquals(editor.prefs, EditorPrefs.decode(saved.single().encode()))
        assertEquals(EditorPrefs(), EditorPrefs.decode("not json"))
    }

    @Test
    fun favouritesComeFirstAndDescribingSendsTheSelection() {
        val editor = GraphEditor(chain, spots)
        val last = editor.choices.last()
        editor.favour(last)
        assertEquals(last.key, editor.choices.first().key)
        var sent: Pair<String, Set<String>>? = null
        editor.describe("nothing listens")
        assertNull(sent)
        editor.onDescribe = { text, chosen -> sent = text to chosen }
        editor.select(Selection(setOf("a")))
        editor.describe("  add a cache  ")
        assertEquals("add a cache" to setOf("a"), sent)
    }

    @Test
    fun droppingFromTheShelfAddsWherePointerLands() {
        val editor = GraphEditor(chain, spots)
        editor.attach(Camera())
        editor.carrying = Carry(Template("Sink", NodeKind.Service, "Sink", listOf(into("in"))), Offset(700f, 650f))
        editor.drop(Offset(700f, 650f))
        val added = editor.shape.nodes.single { it.type == "Sink" }
        val at = editor.placement.cards.getValue(added.id)
        assertTrue(at.x in 400.0..700.0 && at.y in 500.0..700.0, at.toString())
        assertNull(editor.carrying)
    }

    @Test
    fun aTrialReadsBackAndWatchingTogglesAPin() {
        val trial = Trial(id = "draft-1", revision = 2, summary = "built", nodes = listOf(TrialNode("b", true), TrialNode("c", false, "check failed")))
        assertEquals(trial, Trial.decode(trial.encode()))
        assertEquals(1, trial.passed)
        assertEquals(1, trial.failed)
        val editor = GraphEditor(chain, spots)
        editor.watch(PinEnd("a", "out", true))
        assertEquals(setOf(PinEnd("a", "out", true)), editor.watched)
        editor.watch(PinEnd("a", "out", true))
        assertTrue(editor.watched.isEmpty())
        assertNotNull(GraphEditor(chain, spots).commands.commands.firstOrNull { it.id.value == "edit-go-9" })
    }
}

private operator fun Placement.plus(more: Map<String, Spot>) = copy(cards = cards + more)
