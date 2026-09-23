package dev.shibasis.reaktor.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encodes a bitmap as PNG bytes.
 *
 * The missing half of drawing a composable to an image. `GraphicsLayer.toImageBitmap()` is common
 * code and gets an app as far as pixels in memory, but every way of getting those pixels *out* —
 * into a share sheet, a file, an upload — needs bytes, and the encoders are platform APIs. So an
 * app that wants to hand someone a picture of its own UI ends up writing expect/actual itself.
 *
 * Null when encoding fails, which on both platforms means an unreadable or already-recycled
 * bitmap. Lossless: PNG rather than JPEG because these are screenshots of interface, where
 * flat colour and text are exactly what block-based compression ruins.
 */
expect fun ImageBitmap.encodePng(): ByteArray?
