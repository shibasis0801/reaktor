package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/** Encodes a captured frame. PNG rather than JPEG because a UI capture is flat colour and text. */
expect fun ImageBitmap.encodeToPngBytes(): ByteArray?

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
        val bitmap = runCatching { layer.toImageBitmap() }.getOrNull()
            ?: return ScreenshotResponse("", 0, 0, "The surface has not drawn yet")
        val png = bitmap.encodeToPngBytes()
            ?: return ScreenshotResponse("", bitmap.width, bitmap.height, "This platform cannot encode a PNG")
        return ScreenshotResponse(
            pngBase64 = png.encodeBase64(),
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
internal fun Modifier.captureInto(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

@Composable
internal fun rememberCaptureLayer(): GraphicsLayer = rememberGraphicsLayer()

/**
 * Base64 without a dependency.
 *
 * The agent already pays for kotlinx-serialization; adding an encoder library for forty lines of
 * table lookup would be the wrong trade on a mobile binary.
 */
internal fun ByteArray.encodeBase64(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder((size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < size) {
        val value = (this[index].toInt() and 0xFF shl 16) or
            (this[index + 1].toInt() and 0xFF shl 8) or
            (this[index + 2].toInt() and 0xFF)
        out.append(alphabet[value shr 18 and 0x3F])
        out.append(alphabet[value shr 12 and 0x3F])
        out.append(alphabet[value shr 6 and 0x3F])
        out.append(alphabet[value and 0x3F])
        index += 3
    }
    when (size - index) {
        1 -> {
            val value = this[index].toInt() and 0xFF shl 16
            out.append(alphabet[value shr 18 and 0x3F])
            out.append(alphabet[value shr 12 and 0x3F])
            out.append("==")
        }

        2 -> {
            val value = (this[index].toInt() and 0xFF shl 16) or (this[index + 1].toInt() and 0xFF shl 8)
            out.append(alphabet[value shr 18 and 0x3F])
            out.append(alphabet[value shr 12 and 0x3F])
            out.append(alphabet[value shr 6 and 0x3F])
            out.append('=')
        }
    }
    return out.toString()
}
