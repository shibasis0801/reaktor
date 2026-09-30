package dev.shibasis.reaktor.devtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Compose on Apple draws through Skia, so the same encoder serves here and on the desktop. */
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

internal actual suspend fun GraphicsLayer.readPixels(): ImageBitmap? {
    val picture = onMain { recordPicture() } ?: return null
    try {
        val bitmap = Bitmap().apply { allocN32Pixels(picture.cullRect.width.toInt(), picture.cullRect.height.toInt()) }
        val canvas = Canvas(bitmap)
        try {
            canvas.drawPicture(picture)
        } finally {
            canvas.close()
        }
        return bitmap.asComposeImageBitmap()
    } finally {
        picture.close()
    }
}

private fun GraphicsLayer.recordPicture(): Picture? {
    if (size.width <= 0 || size.height <= 0) return null
    val recorder = PictureRecorder()
    try {
        val canvas = recorder.beginRecording(Rect.makeWH(size.width.toFloat(), size.height.toFloat()))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas.asComposeCanvas(), size.toSize()) { drawLayer(this@recordPicture) }
        return recorder.finishRecordingAsPicture()
    } finally {
        recorder.close()
    }
}
