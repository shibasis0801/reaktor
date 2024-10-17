package dev.shibasis.reaktor.media.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun ByteArray.toImageBitmap() = Image.makeFromEncoded(this).toComposeImageBitmap()

actual fun ImageBitmap.toByteArray(): ByteArray {
    TODO("Implement using Skia later.")
}