@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*

class BlueprintBitmapCameraTest {
    @Test fun bitmapFollowsTheCardOnTheVeryFirstPanAndZoomFrame() {
        val layout = BlueprintEngine.layout("bitmap", listOf(BlueprintGroup("app", "App", listOf(BlueprintNode("screen", 0, rows = 30)))), emptyList(), 1.6)
        val card = layout.cards.getValue("screen")
        val bitmap = ImageBitmap(24, 48).also { Canvas(it).drawRect(Rect(0f, 0f, 24f, 48f), Paint().apply { color = Color.Magenta }) }
        for (density in listOf(1f, 2f)) {
            val canvas = GraphCanvasState()
            lateinit var scene: ImageComposeScene
            var time = 0L
            fun frame(check: Boolean = false) = SwingUtilities.invokeAndWait {
                Snapshot.sendApplyNotifications(); time += 16_666_667L
                scene.render(time).use { image -> if (check) {
                    val view = canvas.viewport()
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    fun pixel(x: Double, y: Double) = pixels[(density * (view.x + x * view.zoom)).roundToInt(), (density * (view.y + y * view.zoom)).roundToInt()]
                    assertEquals(Color.Magenta, pixel(card.x + card.width / 2, card.y + BlueprintEngine.HeaderHeight + 40), "Bitmap detached from its card at $density / ${view.zoom}")
                    assertNotEquals(Color.Magenta, pixel(card.x - 6, card.y + card.height / 2), "Bitmap escaped its card")
                    assertNotEquals(Color.Magenta, pixel(card.x + card.width / 2, card.y + 12), "Bitmap covered its route")
                } }
            }
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1440 * density).toInt(), (1000 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "1 screen") },
                        cardLook = { BlueprintCardLook(Color.White, BlueprintBench.icon, "/screen", "Screen", image = bitmap) },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize())
                }
            }
            try {
                repeat(20) { frame(); Thread.sleep(10) }
                for (zoom in listOf(.12, .19, .35, .7, 1.1)) {
                    SwingUtilities.invokeAndWait { canvas.restore(680 - (card.x + card.width / 2) * zoom, 480 - (card.y + card.height / 2) * zoom, zoom) }
                    frame(true)
                    SwingUtilities.invokeAndWait { val view = canvas.viewport(); canvas.restore(view.x + 75, view.y - 30, view.zoom) }
                    frame(true)
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }
}
