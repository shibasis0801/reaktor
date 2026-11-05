package dev.shibasis.reaktor.blueprint.product

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SurfaceGraphExplorerTest {
    private val source = Card("source", 80.0, 120.0, 480.0, 180.0, listOf(
        Pin("raw:key", "DomainValue", false, 70.0, PortWiring.Connected, "source-row", listOf("row-alias")),
        Pin("raw:key", "DomainValue", true, 70.0, PortWiring.Connected, "source-row", listOf("row-alias"))), 0)
    private val target = Card("target", 700.0, 120.0, 440.0, 160.0,
        listOf(Pin("sink:key", "DomainValue", false, 70.0, PortWiring.Connected, "target-row")), 0)
    private val wire = Link("original-wire", source.id, target.id, "raw:key", "sink:key", LinkKind.Wire,
        listOf("original-wire"), listOf(560.0 to 190.0, 700.0 to 190.0), family = "chapter")
    private val layout = BlueprintLayout("declared", linkedMapOf(source.id to source, target.id to target),
        listOf(Frame("area", "Area", 40.0, 60.0, 1140.0, 300.0, setOf(source.id, target.id), emptyList(), false)), listOf(wire), 1180.0, 360.0)
    private val nodes = listOf(
        SurfaceGraphNode(source.id, "Source sheet", "Book", "area", rows = listOf(SurfaceGraphRow("source-row", "Original source row", "raw:key", listOf("row-alias"))), opens = true),
        SurfaceGraphNode(target.id, "Target sheet", "Course", "area", rows = listOf(SurfaceGraphRow("target-row", "Original target row", "sink:key"))))
    private val edge = SurfaceGraphLink("original-wire", "source-row", "target-row", "Recorded prerequisite")
    private val appearance = SurfaceGraphAppearance(Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.Blue)
    private fun presentation() = SurfaceGraphExplorerPresentation(layout, nodes, listOf(edge), listOf(SurfaceGraphGroup("area", "Area")))

    @Test fun suppliedFullLayoutAndDirectionalTypedPinsRemainOriginal() {
        val model = presentation()
        assertSame(layout, model.full)
        assertSame(source, model.full.cards.getValue(source.id))
        assertEquals(listOf(PinRef("source", "raw:key", false), PinRef("source", "raw:key", true)), source.pins.map(source::ref))
        assertTrue(model.full.cards.values.flatMap { it.pins }.all { it.type == "DomainValue" })
        assertSame(wire, model.full.links.single())
    }

    @Test fun sourceWireAndBundleMembersResolveToOriginalRowEndpoints() {
        val model = presentation()
        assertSame(edge, model.source(wire))
        assertSame(edge, model.source(wire.copy(id = "corridor")))
        assertEquals("target-row", model.target(wire, "row-alias"))
        assertEquals("source-row", model.target(wire, "target-row"))
        assertEquals(source.id, model.owner("row-alias"))
        assertEquals(target.id, model.owner("target-row"))
    }

    @Test fun rowLooksAndOpenActionUseTheOriginalDeclaredCard() {
        val opened = mutableListOf<String>()
        val look = presentation().look(source, appearance, "row-alias", emptySet(), { opened.add(it) })!!
        assertTrue(look.selected)
        assertEquals(2, look.pins.size)
        assertTrue(look.pins.all { it?.text == "Original source row" && it.selected })
        look.actions.single().onInvoke()
        assertEquals(listOf(source.id), opened)
        assertEquals("Open Source sheet", look.actions.single().label)
        assertTrue(presentation().look(source, appearance, null, emptySet(), null)!!.actions.isEmpty())
    }

    @Test fun duplicateRawKeysUseTheOriginalDirectionalRowIdentity() {
        val directional = source.copy(pins = listOf(
            source.pins.first().copy(id = "input-row", aliases = emptyList()),
            source.pins.last().copy(id = "output-row", aliases = listOf("output-alias"))))
        val node = nodes.first().copy(rows = listOf(
            SurfaceGraphRow("input-row", "Original input row", "raw:key"),
            SurfaceGraphRow("output-row", "Original output row", "raw:key", listOf("output-alias"))))
        val full = layout.copy(cards = layout.cards + (source.id to directional))
        val model = SurfaceGraphExplorerPresentation(full, listOf(node, nodes.last()), listOf(edge), emptyList())
        val look = model.look(directional, appearance, "output-alias", emptySet(), null)!!
        assertEquals(listOf("Original input row", "Original output row"), look.pins.map { it?.text })
        assertEquals(listOf(false, true), look.pins.map { it?.selected })
        assertEquals(listOf(false, true), directional.pins.map { it.provides })
        assertEquals(listOf("raw:key", "raw:key"), directional.pins.map { it.key })
        assertSame(full, model.full)
    }

    @Test fun selectionAndOverviewBackKeepTheSavedCameraAndDeclaredGeometry() {
        val model = presentation()
        val state = SurfaceGraphExplorerState().apply { resize(IntSize(1440, 900)); frames = layout.frames; place(layout.cards.values.toList(), nodes, "same") }
        val saved = Viewport(14.0, 27.0, 2.0)
        state.flow.setViewport(saved)
        model.look(source, appearance, "row-alias", setOf(target.id), null)
        state.place(layout.cards.values.toList(), nodes, "same")
        assertEquals(saved, state.viewport())
        assertEquals(layout.cards.values.toList(), state.cards)
        state.overview()
        assertFalse(saved == state.viewport())
        state.flow.setViewport(saved)
        assertEquals(saved, state.viewport())
        assertEquals(layout.cards.values.toList(), state.cards)
    }
}
