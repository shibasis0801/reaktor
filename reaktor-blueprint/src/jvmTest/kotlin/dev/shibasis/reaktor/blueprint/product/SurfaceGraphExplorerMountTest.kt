@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

class SurfaceGraphExplorerMountTest {
    @Test fun lightMountKeepsTheSuppliedGraphAndCamera() = mount(MachineSignalMode.Light)
    @Test fun darkMountKeepsTheSuppliedGraphAndCamera() = mount(MachineSignalMode.Dark)

    private fun mount(mode: MachineSignalMode) = runBlocking(Dispatchers.Swing) {
        val nodes = listOf(SurfaceGraphNode("source", "Source sheet", "Book", "area", opens = true,
            rows = listOf(SurfaceGraphRow("source-row", "Source row", "raw:key", listOf("row-alias")))),
            SurfaceGraphNode("target", "Target sheet", "Course", "area", rows = listOf(SurfaceGraphRow("target-row", "Target row", "sink:key"))))
        val source = Card("source", 80.0, 120.0, 480.0, 180.0, listOf(
            Pin("raw:key", "DomainValue", false, 70.0, PortWiring.Connected, "source-row", listOf("row-alias")),
            Pin("raw:key", "DomainValue", true, 70.0, PortWiring.Connected, "source-row", listOf("row-alias"))), 0)
        val target = Card("target", 700.0, 120.0, 440.0, 160.0, listOf(Pin("sink:key", "DomainValue", false, 70.0, PortWiring.Connected, "target-row")), 0)
        val wire = Link("original-wire", source.id, target.id, "raw:key", "sink:key", LinkKind.Wire, listOf("original-wire"), listOf(560.0 to 190.0, 700.0 to 190.0), family = "chapter")
        val layout = BlueprintLayout("declared", linkedMapOf(source.id to source, target.id to target),
            listOf(Frame("area", "Area", 40.0, 60.0, 1140.0, 300.0, setOf(source.id, target.id), emptyList(), false)), listOf(wire), 1180.0, 360.0)
        val selected = mutableStateOf<String?>(null)
        val state = SurfaceGraphExplorerState()
        var opened: String? = null
        val scene = ImageComposeScene(1512, 982, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                val colors = MachineSignal.colors
                SurfaceGraphExplorer(nodes, listOf(SurfaceGraphLink("original-wire", "source-row", "target-row")), selected.value,
                    SurfaceGraphAppearance(colors.canvas, colors.surface, colors.textStrong, colors.textMuted, colors.line, colors.controlAccent),
                    state, { selected.value = it }, Modifier.fillMaxSize(), layout = layout, key = "fixed-source",
                    groups = listOf(SurfaceGraphGroup("area", "Area")), onOpen = { opened = it; true })
            }
        }
        var time = 0L
        suspend fun frames() { repeat(16) { Snapshot.sendApplyNotifications(); scene.render(++time * 16_666_667L).close(); delay(5) } }
        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        }
        fun point(x: Double, y: Double) = state.viewport().let { Offset((it.x + x * it.zoom).toFloat(), (it.y + y * it.zoom).toFloat()) }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        try {
            frames()
            assertEquals(layout.cards.values.toList(), state.cards)
            assertSame(source, state.cards.first())
            assertSame(source.pins.first(), state.cards.first().pins.first())
            state.centre(source.id, level = 2.0); repeat(4) { frames() }
            val camera = state.viewport()
            click(point(source.x + source.width / 4, source.y + source.pins.first().y)); frames()
            assertEquals("source-row", selected.value)
            selected.value = "row-alias"; frames()
            assertEquals(camera, state.viewport())
            val action = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "blueprint-card-action:source:open"
            }
            click(action.boundsInRoot.center); frames()
            assertEquals("source", opened)
            assertEquals("row-alias", selected.value)
            assertEquals(camera, state.viewport())
            click(point(630.0, 190.0)); frames()
            assertEquals("target-row", selected.value)
            assertEquals(camera, state.viewport())
            for (zoom in listOf(.02, .5, 2.0, 8.0, 16.0, .02, 2.0)) {
                state.restore(camera.x, camera.y, zoom); frames()
                assertEquals(layout.cards.values.toList(), state.cards)
                assertEquals(layout.frames, state.frames)
                assertSame(source.pins.last(), state.cards.first().pins.last())
            }
            assertEquals(listOf("original-wire"), layout.links.flatMap { it.members })
        } finally { scene.close() }
    }
}
