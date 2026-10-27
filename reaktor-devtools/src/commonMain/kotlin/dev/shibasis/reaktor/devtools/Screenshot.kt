package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.io.encoding.Base64

/** Encodes a captured frame. PNG rather than JPEG because a UI capture is flat colour and text. */
expect fun ImageBitmap.encodeToPngBytes(): ByteArray?

internal expect suspend fun GraphicsLayer.readPixels(): ImageBitmap?

/**
 * Captures the app's own surface.
 *
 * The host can already screenshot a device — `adb exec-out screencap`, `simctl io screenshot` —
 * so this exists for the thing those cannot do: the capture comes from the same composition that
 * produced the element tree, so a frame and its bounds describe the same instant. A host capture
 * taken a moment later does not.
 */
class ComposeScreenshotProvider(private val layer: GraphicsLayer) : ScreenshotProvider {
    override suspend fun capture(): ScreenshotResponse {
        val bitmap = layer.readPixels()
            ?: return ScreenshotResponse("", 0, 0, "The surface has not drawn yet")
        val png = bitmap.encodeToPngBytes()
            ?: return ScreenshotResponse("", bitmap.width, bitmap.height, "This platform cannot encode a PNG")
        return ScreenshotResponse(
            pngBase64 = Base64.encode(png),
            widthPixels = bitmap.width,
            heightPixels = bitmap.height,
        )
    }
}

/**
 * Records the composition into a layer the agent can read back.
 *
 * Applied by [ReaktorDevTools] at the root, so a capture costs one extra layer rather than a
 * redraw. The layer is recorded on every frame the app was drawing anyway.
 */
@Composable
internal fun Modifier.captureInto(layer: GraphicsLayer, onDraw: () -> Unit): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
    onDraw()
}

@Composable
internal fun rememberCaptureLayer(): GraphicsLayer = rememberGraphicsLayer()

