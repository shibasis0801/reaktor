package dev.shibasis.reaktor.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodePng(): ByteArray? = runCatching {
    ByteArrayOutputStream().use { stream ->
        // Quality is ignored for PNG; it is lossless whatever is passed.
        asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
}.getOrNull()
