package dev.shibasis.reaktor.devtools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = runCatching {
    ByteArrayOutputStream().use { stream ->
        asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
}.getOrNull()

actual fun ImageBitmap.encodeFrame(maxDimension: Int, quality: Int): EncodedFrame? = runCatching {
    val captured = asAndroidBitmap()
    val source = if (android.os.Build.VERSION.SDK_INT >= 26 && captured.config == Bitmap.Config.HARDWARE) {
        captured.copy(Bitmap.Config.ARGB_8888, false)
    } else captured
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
    val frame = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
    } else source
    val bytes = ByteArrayOutputStream().use { stream ->
        frame.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        stream.toByteArray()
    }
    EncodedFrame(bytes, frame.width, frame.height)
}.getOrNull()

internal actual suspend fun GraphicsLayer.readPixels(): ImageBitmap? =
    runCatching { toImageBitmap() }.getOrNull()
        ?: runCatching { withContext(Dispatchers.Main) { toImageBitmap() } }.getOrNull()
