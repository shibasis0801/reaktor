@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintMapLabelTest {
    @Test fun labelsStayConstantPinnedAndNonOverlappingAcrossTheLadder() {
        val outer = Frame("outer", "System", 0.0, 0.0, 5000.0, 3000.0, emptySet(), emptyList(), false)
        val inner = Frame("inner", "Component", 100.0, 100.0, 3000.0, 2000.0, emptySet(), emptyList(), false, parent = outer.key)
        for (mode in listOf(MachineSignalMode.Light, MachineSignalMode.Dark)) {
            val layout = BlueprintLayout("labels", emptyMap(), listOf(outer, inner), emptyList(), outer.width, outer.height)
            withScene(layout, mode) { scene, canvas, settle, picture ->
                settle()
                var width: Float? = null
                for (zoom in listOf(.16, .5, 1.0, 4.0, 16.0)) {
                    edt { canvas.restore(Viewport(-500.0 * zoom, -500.0 * zoom, zoom)) }; settle()
                    val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
                    val labels = field(painter, "mapLabels") as List<*>
                    val boxes = labels.filterIsInstance<Rect>()
                    assertTrue(boxes.isNotEmpty(), "No pinned label at $zoom")
                    assertTrue(boxes.first().top >= 0f)
                    width?.let { assertEquals(it, boxes.first().width, .001f) }
                    width = boxes.first().width
                    for (a in boxes.indices) for (b in a + 1 until boxes.size) assertTrue(!boxes[a].overlaps(boxes[b]))
                    picture("${mode.name.lowercase()}-$zoom")
                }
            }
        }
    }

    @Test fun boundsExposeTheExactBannerOverflowAndExcludeNestedAndUnrelatedFrames() {
        val outer = Frame("outer", "Resources", 100.0, 100.0, 100.0, 300.0, emptySet(), emptyList(), false)
        val inner = outer.copy(key = "inner", parent = outer.key)
        val outside = outer.copy(key = "outside", x = 5000.0)
        val layout = BlueprintLayout("bounds", emptyMap(), listOf(outer, inner, outside), emptyList(), 5200.0, 400.0)
        withScene(layout, MachineSignalMode.Dark) { _, canvas, settle, _ ->
            settle()
            val painter = (canvas.reading as kotlin.jvm.internal.CallableReference).boundReceiver as BlueprintPainter
            for (zoom in listOf(.1, .5, 1.0, 16.0)) {
                val boxes = painter.paintedBounds(outer, zoom)
                assertEquals(1, boxes.size)
                val box = boxes.single()
                assertEquals(outer.x, box.x)
                assertEquals(30.0 / zoom, box.height, .001)
                assertTrue(box.width >= outer.width)
                assertTrue(box.y <= outer.y + BlueprintEngine.FrameTop + .001)
                assertTrue(box.y + box.height <= outer.y + outer.height + .001)
            }
            assertTrue(painter.paintedBounds(outer.copy(x = 2000.0), 1.0).isEmpty())
            assertTrue(painter.paintedBounds(outer, Double.NaN).isEmpty())
            assertTrue(painter.paintedBounds(outer, 0.0).isEmpty())
        }
    }

    private fun withScene(layout: BlueprintLayout<String>, mode: MachineSignalMode,
                          block: (ImageComposeScene, GraphCanvasState, () -> Unit, (String) -> Unit) -> Unit) {
        val canvas = GraphCanvasState(.002..16.0)
        lateinit var scene: ImageComposeScene
        var tick = 0L
        fun settle() { repeat(32) { edt { Snapshot.sendApplyNotifications(); scene.render(++tick * 16_666_667L).close() }; Thread.sleep(3) } }
        edt {
            scene = ImageComposeScene(1100, 700, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    BlueprintCanvas(layout, canvas, emptySet(), null, cardLook = { null },
                        frameLook = { BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes") }, linkStyle = { Blueprint.link(Color.Blue, false, false) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try {
            block(scene, canvas, ::settle) { name -> edt {
                val folder = File("build/reports/blueprint-map-labels").apply { mkdirs() }
                scene.render(++tick * 16_666_667L).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
            } }
        } finally { edt { scene.close() } }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
