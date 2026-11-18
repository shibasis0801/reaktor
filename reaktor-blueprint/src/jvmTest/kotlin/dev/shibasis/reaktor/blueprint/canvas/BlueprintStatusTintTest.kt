@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

class BlueprintStatusTintTest {
    @Test fun anExplicitFillKeepsTheStatusTintAtOverviewAndTitleZoom() {
        for (mode in MachineSignalMode.entries) for (zoom in listOf(.12, .3)) {
            val colors = pixels(mode, zoom)
            assertNotEquals(colors[0], colors[1], "Two status tones remain distinct over an explicit fill at $zoom in $mode")
        }
    }

    @Test fun aFrameWithALitStatusTintsItsBodyWithoutFocus() {
        for (mode in MachineSignalMode.entries) {
            val colors = pixels(mode, .3)
            assertNotEquals(colors[2], colors[3], "An unfocused frame keeps its own status in $mode")
        }
    }

    private fun pixels(mode: MachineSignalMode, zoom: Double): List<Int> {
        val cards = listOf(Card("yellow", 80.0, 100.0, 600.0, 420.0, emptyList(), 0),
            Card("blue", 980.0, 100.0, 600.0, 420.0, emptyList(), 0))
        val frames = listOf(Frame("yellow-frame", "Yellow", 0.0, 0.0, 800.0, 650.0, setOf("yellow"), emptyList(), false),
            Frame("blue-frame", "Blue", 900.0, 0.0, 800.0, 650.0, setOf("blue"), emptyList(), false))
        val layout = BlueprintLayout("status-tint", cards.associateBy { it.id }, frames, emptyList(), 1700.0, 650.0)
        val canvas = GraphCanvasState(.002..16.0)
        var result = emptyList<Int>()
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(800, 450, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, mode = mode) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        cardLook = { card -> BlueprintCardLook(if (card.id == "yellow") Color(0xFFE0B522) else Color(0xFF2584DD),
                            SurfaceGraphSymbol, "Title", "", fill = MachineSignal.colors.card) },
                        frameLook = { frame -> BlueprintFrameLook(SurfaceGraphSymbol, frame.label,
                            lit = if (frame.key == "yellow-frame") Color(0xFFE0B522) else Color(0xFF2584DD)) },
                        linkStyle = { Blueprint.link(Color.Gray, false, false) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, initialFit = {},
                        mapDetail = true, modifier = Modifier.fillMaxSize())
                }
            }
            try {
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
                canvas.restore(30.0, 40.0, zoom)
                repeat(8) { Snapshot.sendApplyNotifications(); scene.render((it + 12) * 16_666_667L).close() }
                scene.render(30 * 16_666_667L).use { image ->
                    val bytes = image.encodeToData()!!.use { it.bytes }
                    val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                    val view = canvas.viewport()
                    result = listOf(380.0 to 400.0, 1280.0 to 400.0, 750.0 to 600.0, 1650.0 to 600.0).map { (x, y) ->
                        bitmap.getRGB((view.x + x * view.zoom).toInt(), (view.y + y * view.zoom).toInt())
                    }
                }
            } finally { scene.close() }
        }
        return result
    }
}
