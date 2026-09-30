package dev.shibasis.reaktor.devtools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = runCatching {
    ByteArrayOutputStream().use { stream ->
        asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
}.getOrNull()
