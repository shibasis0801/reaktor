@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import dev.shibasis.reaktor.blueprint.*
import dev.shibasis.reaktor.ui.machinesignal.*
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotEquals

class BlueprintProseIconSpacingTest {
    @Test fun theKindIconLeavesAGapAfterTheStatusStripe() {
        val icon = ImageVector.Builder(defaultWidth = MachineSignal.Editor.smallIconSize,
            defaultHeight = MachineSignal.Editor.smallIconSize, viewportWidth = 14f, viewportHeight = 14f).apply {
            path(fill = SolidColor(Color.White)) { moveTo(0f, 0f); lineTo(14f, 0f); lineTo(14f, 14f); lineTo(0f, 14f); close() }
        }.build()
        val card = Card("item", 40.0, 40.0, 280.0, 210.0, emptyList(), 0)
        val layout = BlueprintLayout("prose-icon-gap", mapOf(card.id to card), emptyList(), emptyList(), 400.0, 300.0)
        val canvas = GraphCanvasState(.01..4.0)
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined) {
                MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        cardLook = { BlueprintCardLook(Color.Magenta, icon, "A task title", "Ready", face = BlueprintFace.Prose, eyebrow = "Task") },
                        frameLook = { BlueprintFrameLook(icon, "") }, linkStyle = { Blueprint.link(Color.Blue, false, false) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, initialFit = {}, modifier = Modifier.fillMaxSize())
                }
            }
            try {
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render(it * 16_666_667L).close() }
                canvas.restore(40.0, 40.0, 1.0)
                repeat(12) { Snapshot.sendApplyNotifications(); scene.render((it + 12) * 16_666_667L).close() }
                scene.render(30 * 16_666_667L).use { image ->
                    val bytes = image.encodeToData()!!.use { it.bytes }
                    val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                    val view = canvas.viewport()
                    fun pixel(x: Double) = bitmap.getRGB((view.x + card.x + x).toInt(), (view.y + card.y + 12).toInt())
                    assertNotEquals(pixel(6.0), pixel(12.0), "The gap after the stripe is separate from the solid kind icon")
                }
            } finally { scene.close() }
        }
    }
}
