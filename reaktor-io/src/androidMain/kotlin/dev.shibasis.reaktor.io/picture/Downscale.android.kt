package dev.shibasis.reaktor.io.picture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual suspend fun downscalePicture(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? = withContext(Dispatchers.Default) {
    // An OutOfMemoryError from a hostile picture is a null here too, not the end of the app.
    runCatching { downscale(bytes, maxSide.coerceAtLeast(1), quality.coerceIn(1, 100)) }.getOrNull()
}

private fun downscale(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_DOWNSCALE_PIXELS) return null

    // Decoded at a half, a quarter, an eighth: never the full twelve megapixels to keep one.
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

    // BitmapFactory gives the pixels as stored. ExifInterface reads the note from JPEG, HEIC and WebP.
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(pictureOrientation(bytes))
    val ratio = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(ratio, ratio)
        when (orientation) {
            2 -> postScale(-1f, 1f)
            3 -> postRotate(180f)
            4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }
            6 -> postRotate(90f)
            7 -> { postRotate(270f); postScale(-1f, 1f) }
            8 -> postRotate(270f)
        }
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val flat = if (!upright.hasAlpha()) upright else Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(upright, 0f, 0f, null) }
    }
    return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
