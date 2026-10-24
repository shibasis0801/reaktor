package dev.shibasis.reaktor.devtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = runCatching {
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}.getOrNull()

actual fun ImageBitmap.encodeFrame(maxDimension: Int, quality: Int): EncodedFrame? = runCatching {
    val image = Image.makeFromBitmap(asSkiaBitmap())
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(image.width, image.height))
    val width = (image.width * scale).toInt()
    val height = (image.height * scale).toInt()
    val frame = if (scale < 1f) {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
        surface.makeImageSnapshot()
    } else image
    val data = frame.encodeToData(EncodedImageFormat.JPEG, quality) ?: return@runCatching null
    EncodedFrame(data.bytes, width, height)
}.getOrNull()

internal actual suspend fun GraphicsLayer.readPixels(): ImageBitmap? = runCatching { toImageBitmap() }.getOrNull()
