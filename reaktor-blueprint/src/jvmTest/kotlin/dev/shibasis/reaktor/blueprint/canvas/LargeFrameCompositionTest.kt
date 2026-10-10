@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class LargeFrameCompositionTest {
    @Test
    fun focusingADistantCardPreservesTheFullFrameWithoutMeasuringItsWorldSize() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                val state = GraphCanvasState()
                val frame = CanvasFrame("workspace", 0.0, 0.0, 126112.0, 178993.0)
                val card = CanvasCard("androidArtifacts", 120000.0, 170000.0, 160.0, 48.0)
                val scene = ImageComposeScene(640, 400, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    GraphCanvas(listOf(frame), listOf(card), emptyList(), state, Color.Black,
                        linkStyle = { LinkStyle(Color.White) },
                        frameContent = { world ->
                            Box(Modifier.fillMaxSize().drawBehind {
                                drawRect(Color.Red, size = Size(world.width.toFloat(), world.height.toFloat()))
                            })
                        },
                        cardContent = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
                        modifier = Modifier.fillMaxSize(), fitKey = "workspace")
                }
                fun render() { repeat(10) { Snapshot.sendApplyNotifications(); scene.render().close() } }
                try {
                    render()
                    state.centre(card.x, card.y, card.width, card.height)
                    render()
                    val pixels = scene.render().use { image ->
                        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                            ImageIO.read(ByteArrayInputStream(data.bytes))
                        }
                    }
                    assertEquals(java.awt.Color.BLUE.rgb, pixels.getRGB(320, 200), "The focused card remains visible")
                    assertEquals(java.awt.Color.RED.rgb, pixels.getRGB(10, 10), "The offscreen header still draws the full frame")
                    assertEquals(0.9, state.zoom)
                } finally { scene.close() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
}
