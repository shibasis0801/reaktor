@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BlueprintCardContentTest {
    @Test
    fun renderedContentFollowsTheCardAtDifferentDensitiesAndZooms() {
        checkContent(12, listOf(.65, 1.1))
        checkContent(72, listOf(.4, .65))
    }

    private fun checkContent(rows: Int, zooms: List<Double>) {
        val layout = BlueprintEngine.layout("preview", listOf(BlueprintGroup("screens", "Screens", listOf(BlueprintNode("screen", 0, rows = rows)))), emptyList(), 1.6)
        val card = layout.cards.getValue("screen")
        val preview = Color.Magenta
        listOf(1f, 2f).forEach { density ->
            val canvas = GraphCanvasState()
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene((1440 * density).toInt(), (900 * density).toInt(), Density(density), coroutineContext = Dispatchers.Unconfined) {
                    BlueprintCanvas(layout, canvas, emptySet(), null,
                        linkStyle = { Blueprint.link(Color.White, false, false) },
                        frameLook = { BlueprintFrameLook(BlueprintBench.icon, "1 screen") },
                        cardLook = { BlueprintCardLook(Color.White, BlueprintBench.icon, "/screen", "Screen") },
                        onCardClick = {}, onLinkClick = {}, onPaneClick = {}, modifier = Modifier.fillMaxSize(),
                        cardContent = { Box(Modifier.fillMaxSize().background(preview)) })
                }
            }
            var time = 0L
            fun frame() = SwingUtilities.invokeAndWait { Snapshot.sendApplyNotifications(); time += 16_666_667L; scene.render(time).close() }
            fun settle() { repeat(20) { frame(); Thread.sleep(10) } }
            try {
                settle()
                zooms.forEach { zoom ->
                    SwingUtilities.invokeAndWait {
                        canvas.flow.setViewport(Viewport(
                            (720 - (card.x + card.width / 2) * zoom) * density,
                            (450 - (card.y + card.height / 2) * zoom) * density,
                            zoom * density))
                    }
                    settle()
                    SwingUtilities.invokeAndWait {
                        val view = canvas.viewport()
                        scene.render(time + 16_666_667L).use { image ->
                            val pixels = image.toComposeImageBitmap().toPixelMap()
                            fun pixel(x: Double, y: Double) = pixels[
                                (density * (view.x + x * view.zoom)).roundToInt(),
                                (density * (view.y + y * view.zoom)).roundToInt()]
                            assertEquals(preview, pixel(card.x + card.width / 2, card.y + BlueprintEngine.HeaderHeight + 20), "Preview body at density $density, zoom $zoom")
                            assertNotEquals(preview, pixel(card.x + card.width / 2, card.y + 12), "Preview covered its route header")
                            assertNotEquals(preview, pixel(card.x - 6, card.y + card.height / 2), "Preview escaped its card")
                        }
                    }
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }
}
