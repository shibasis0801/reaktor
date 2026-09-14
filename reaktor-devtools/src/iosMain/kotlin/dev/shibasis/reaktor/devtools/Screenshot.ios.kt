package dev.shibasis.reaktor.devtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Compose on Apple draws through Skia, so the same encoder serves here and on the desktop. */
actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = runCatching {
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}.getOrNull()
