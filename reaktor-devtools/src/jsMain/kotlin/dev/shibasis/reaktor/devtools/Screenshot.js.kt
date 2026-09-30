package dev.shibasis.reaktor.devtools

import androidx.compose.ui.graphics.ImageBitmap

/** A browser agent is reached differently and captures through the page, not through Skia. */
actual fun ImageBitmap.encodeToPngBytes(): ByteArray? = null
