@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.product

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.Card
import dev.shibasis.reaktor.blueprint.Frame
import dev.shibasis.reaktor.blueprint.Link
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.WireShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import dev.shibasis.reaktor.ui.machinesignal.colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SurfaceGraphExplorerGestureTest {
    @Test fun lightSuppliedPortsKeepDirectionAndGeometryThroughMountedGestures() = mounted(MachineSignalMode.Light)
    @Test fun darkSuppliedPortsKeepDirectionAndGeometryThroughMountedGestures() = mounted(MachineSignalMode.Dark)

    @Test fun lightEditablePortsKeepDirectionGeometryAndAuthorityThroughMountedGestures() = mounted(MachineSignalMode.Light, editable = true)
    @Test fun darkEditablePortsKeepDirectionGeometryAndAuthorityThroughMountedGestures() = mounted(MachineSignalMode.Dark, editable = true)

    private fun mounted(mode: MachineSignalMode, editable: Boolean = false) = runBlocking(Dispatchers.Swing) {
        for (density in listOf(1f, 2f)) for (width in listOf(1100, 1512)) {
            val source = Card("source", 80.0, 120.0, 480.0, 180.0, listOf(
                Pin("raw:key", "InputValue", false, 70.0, PortWiring.Connected, "input-row"),
                Pin("raw:key", "OutputValue", true, 70.0, PortWiring.Connected, "output-row")), 0)
            val target = Card("target", 700.0, 120.0, 440.0, 180.0,
                listOf(Pin("sink:key", "OutputValue", false, 70.0, PortWiring.Connected, "target-row")), 0)
            val wire = Link("original-wire", source.id, target.id, "raw:key", "sink:key", LinkKind.Wire,
                listOf("original-wire"), listOf(560.0 to 190.0, 700.0 to 190.0), family = "chapter")
            val layout = BlueprintLayout("gesture-source", linkedMapOf(source.id to source, target.id to target),
                listOf(Frame("area", "Area", 40.0, 60.0, 1140.0, 300.0, setOf(source.id, target.id), emptyList(), false)),
                listOf(wire), 1180.0, 360.0)
            val nodes = listOf(SurfaceGraphNode(source.id, "Source sheet", "Declared ports", "area", opens = true,
                rows = listOf(SurfaceGraphRow("input-row", "Input row", "raw:key"), SurfaceGraphRow("output-row", "Output row", "raw:key"))),
                SurfaceGraphNode(target.id, "Target sheet", "Declared ports", "area",
                    rows = listOf(SurfaceGraphRow("target-row", "Target row", "sink:key"))))
            val edges = listOf(SurfaceGraphLink(wire.id, "output-row", "target-row", "Observed transition"))
            val groups = listOf(SurfaceGraphGroup("area", "Area"))
            val document = SurfaceGraphDocument(nodes, edges, groups)
            val shape = document.shape.copy(nodes = document.shape.nodes.map { node ->
                node.copy(ports = layout.cards.getValue(node.id).pins.map { PortShape(it.key, it.type, it.provides, it.wiring) })
            }, wires = listOf(WireShape(target.id, "sink:key", source.id, "raw:key", "OutputValue", wire.id)))
            val editor = if (editable) GraphEditor(shape, GraphEditor.placement(layout), single = false,
                pins = { node -> layout.cards.getValue(node.id).pins }, family = { "chapter" }) else null
            val full = editor?.settled ?: layout
            val originalMembers = full.links.flatMap { it.members }
            val originalShape = editor?.shape
            val selected = mutableStateOf<String?>(null)
            val authority: (String?) -> Unit = { selected.value = it }
            editor?.onSelect = authority
            val state = SurfaceGraphExplorerState()
            var opens = 0
            var clears = 0
            val scene = ImageComposeScene((width * density).toInt(), (820 * density).toInt(), Density(density),
                coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    val colors = MachineSignal.colors
                    SurfaceGraphExplorer(nodes, edges,
                        selected.value, SurfaceGraphAppearance(colors.canvas, colors.surface, colors.textStrong, colors.textMuted,
                            colors.line, colors.controlAccent), state, { selected.value = it }, Modifier.fillMaxSize(), layout = full, editor = editor,
                        key = "fixed-source", groups = groups,
                        onClear = { clears++ }, onOpen = { opens++; true })
                }
            }
            var time = 0L
            suspend fun frames() {
                var previous = state.viewport()
                var stable = 0
                repeat(120) {
                    Snapshot.sendApplyNotifications()
                    scene.render(++time * 16_666_667L).close()
                    delay(3)
                    val current = state.viewport()
                    stable = if (current == previous) stable + 1 else 0
                    previous = current
                    if (it >= 11 && stable >= 4 && state.aim == null) return
                }
                error("Explorer viewport did not settle within the bounded frame budget")
            }
            fun point(x: Double, y: Double) = state.flow.viewport.let {
                Offset((it.x + x * it.zoom).toFloat(), (it.y + y * it.zoom).toFloat())
            }
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            }
            fun originals() {
                assertEquals(full.cards.values.toList(), state.cards)
                assertEquals(full.frames, state.frames)
                assertSame(full.cards.getValue(source.id), state.cards.first())
                assertSame(full.cards.getValue(source.id).pins.first(), state.cards.first().pins.first())
                assertSame(full.cards.getValue(source.id).pins.last(), state.cards.first().pins.last())
                assertEquals(source.pins.map { listOf(it.key, it.type, it.provides, it.id) },
                    state.cards.first().pins.map { listOf(it.key, it.type, it.provides, it.id) })
                assertEquals(originalMembers, full.links.flatMap { it.members })
                assertEquals(1, full.links.size)
                if (editor != null) {
                    assertSame(full, editor.settled)
                    assertSame(authority, editor.onSelect)
                    assertSame(originalShape, editor.shape)
                } else assertSame(wire, full.links.single())
            }
            try {
                frames()
                val generation = state.generation
                originals()
                state.centre(source.id, level = 2.0); frames()
                val selectedCamera = state.viewport()
                if (editor == null) {
                    click(point(source.x + source.width / 4, source.y + 70)); frames()
                    assertEquals("input-row", selected.value, "The consumer half must select its original directed row")
                    click(point(source.x + source.width * .75, source.y + 70)); frames()
                    assertEquals("output-row", selected.value, "The provider half must select its original directed row")
                } else {
                    val original = full.cards.getValue(source.id)
                    click(point(original.x + original.width / 2, original.y + 18)); frames()
                    assertEquals(Selection(nodes = setOf(source.id)), editor.selection)
                    assertEquals(source.id, selected.value)
                }
                assertEquals(selectedCamera, state.viewport(), "Original port selection must not move or expand the graph")
                val from = Offset(width * density * .8f, 700 * density)
                val delta = Offset(50 * density, 40 * density)
                if (editable) scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
                scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Move, from + delta / 2f, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Move, from + delta, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, from + delta, button = PointerButton.Primary)
                if (editable) scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
                frames()
                val panned = state.viewport()
                assertEquals(selectedCamera.x + 50, panned.x, .001, "Background drag must pan at the actual density")
                assertEquals(selectedCamera.y + 40, panned.y, .001)
                assertEquals(selectedCamera.zoom, panned.zoom)
                assertEquals(0, clears, "Dragging must not dispatch the pane selection action")
                val anchor = Offset(width * density * .6f, 600 * density)
                val wheelStart = state.viewport()
                scene.sendPointerEvent(PointerEventType.Scroll, anchor, scrollDelta = Offset(1f, -1f))
                frames()
                val wheeled = state.viewport()
                assertEquals(wheelStart.zoom, wheeled.zoom, "Every unmodified scroll must pan without zooming")
                assertTrue(wheelStart.x != wheeled.x && wheelStart.y != wheeled.y, "Both scroll axes must pan")
                scene.sendPointerEvent(PointerEventType.Scroll, anchor, scrollDelta = Offset(1f, 0f),
                    keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true))
                frames()
                val shifted = state.viewport()
                assertEquals(wheeled.zoom, shifted.zoom, "Shift horizontal scrolling must never zoom")
                assertTrue(wheeled.x != shifted.x)
                assertEquals(wheeled.y, shifted.y, .001)
                val before = state.flow.viewport
                val worldX = (anchor.x - before.x) / before.zoom
                val worldY = (anchor.y - before.y) / before.zoom
                scene.sendPointerEvent(PointerEventType.Scroll, anchor, scrollDelta = Offset(0f, -1f),
                    keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = true))
                frames()
                val after = state.flow.viewport
                assertTrue(after.zoom > before.zoom, "Modified wheel must zoom the actual Explorer mount")
                assertTrue(abs((anchor.x - after.x) / after.zoom - worldX) < .001, "Zoom must retain the pointer's world X")
                assertTrue(abs((anchor.y - after.y) / after.zoom - worldY) < .001, "Zoom must retain the pointer's world Y")
                originals()
                state.overview(); frames()
                val fit = state.viewport()
                for (level in listOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
                    state.centre(source.id, level = level); frames(); originals()
                    assertEquals(level, state.zoom, .001)
                    assertEquals(generation, state.generation, "Zoom must not rebuild the supplied full layout")
                }
                state.overview(); frames()
                assertEquals(fit, state.viewport())
                state.restore(0.0, 0.0, .002); frames()
                state.reveal(target.id, row = 0, readable = true); frames()
                assertTrue(state.zoom >= .45, "Explicit readable reveal must leave the far overview")
                val view = state.viewport()
                val rowX = view.x + (target.x + target.width / 2) * view.zoom
                val rowY = view.y + (target.y + target.pins.single().y) * view.zoom
                assertTrue(rowX in 0.0..width.toDouble() && rowY in 0.0..820.0, "The exact original row must be in the viewport")
                originals()
                assertEquals(if (editable) source.id else "output-row", selected.value)
                assertEquals(0, opens, "Pan, zoom and port selection must not invoke source-open authority")
            } finally { scene.close() }
        }
    }
}
