package dev.shibasis.reaktor.io.picture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt

actual suspend fun downscalePicture(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? = withContext(Dispatchers.Default) {
    runCatching { downscale(bytes, maxSide.coerceAtLeast(1), quality.coerceIn(1, 100)) }.getOrNull()
}

private fun downscale(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? {
    // ImageIO decodes at full size, so what the header asks for is checked before it is asked to.
    val header = pictureHeader(bytes) ?: return null
    if (header.pixels > MAX_DOWNSCALE_PIXELS) return null
    val stored = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null

    // ImageIO gives the pixels as stored; the EXIF note says how to turn them.
    val sw = stored.width.toDouble()
    val sh = stored.height.toDouble()
    val orientation = pictureOrientation(bytes)
    val upright = when (orientation) {
        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, sw, 0.0)
        3 -> AffineTransform.getRotateInstance(PI, sw / 2, sh / 2)
        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, sh)
        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, sh, 0.0)
        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, sh, sw)
        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, sw)
        else -> AffineTransform()
    }
    val onItsSide = orientation in 5..8
    val uw = if (onItsSide) sh else sw
    val uh = if (onItsSide) sw else sh
    val ratio = minOf(1.0, maxSide / maxOf(uw, uh))
    val w = (uw * ratio).roundToInt().coerceAtLeast(1)
    val h = (uh * ratio).roundToInt().coerceAtLeast(1)

    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val canvas = out.createGraphics()
    try {
        // White under anything see-through: JPEG has no transparency.
        canvas.color = Color.WHITE
        canvas.fillRect(0, 0, w, h)
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        canvas.drawImage(stored, AffineTransform.getScaleInstance(ratio, ratio).apply { concatenate(upright) }, null)
    } finally {
        canvas.dispose()
    }

    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
    val bytesOut = ByteArrayOutputStream()
    try {
        MemoryCacheImageOutputStream(bytesOut).use { stream ->
            writer.output = stream
            val settings = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            writer.write(null, IIOImage(out, null, null), settings)
        }
    } finally {
        writer.dispose()
    }
    return bytesOut.toByteArray()
}
