package dev.shibasis.reaktor.devtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer

/** A browser agent is reached differently and captures through the page, not through Skia. */
actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = null

actual fun ImageBitmap.encodeFrame(maxDimension: Int, quality: Int): EncodedFrame? = null

internal actual suspend fun GraphicsLayer.readPixels(): ImageBitmap? = runCatching { toImageBitmap() }.getOrNull()
