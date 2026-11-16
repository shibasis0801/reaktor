package dev.shibasis.reaktor.io.picture

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In a browser (window or worker), through `createImageBitmap` and an `OffscreenCanvas`. Where
 * those don't exist (Node, an old browser) there is nothing to decode with, and this is null.
 */
actual suspend fun downscalePicture(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? {
    // The browser's decoder allocates what the header asks for; ask the header first.
    val header = pictureHeader(bytes) ?: return null
    if (header.pixels > MAX_DOWNSCALE_PIXELS) return null
    val scope: dynamic = js("globalThis")
    if (scope.createImageBitmap == undefined || scope.OffscreenCanvas == undefined || scope.Blob == undefined) return null
    return runCatching {
        val type = header.format.mime
        // A Kotlin ByteArray is an Int8Array, which a Blob takes as it is.
        val blob: dynamic = js("new Blob([bytes], { type: type })")
        val orient: dynamic = js("({ imageOrientation: 'from-image' })")
        val bitmap: dynamic = (scope.createImageBitmap(blob, orient) as Promise<dynamic>).await()
        val width = (bitmap.width as Number).toDouble()
        val height = (bitmap.height as Number).toDouble()
        val ratio = minOf(1.0, maxSide.coerceAtLeast(1) / max(width, height))
        val w = (width * ratio).roundToInt().coerceAtLeast(1)
        val h = (height * ratio).roundToInt().coerceAtLeast(1)
        val canvas: dynamic = js("new OffscreenCanvas(w, h)")
        val context: dynamic = canvas.getContext("2d")
        context.fillStyle = "#ffffff"
        context.fillRect(0, 0, w, h)
        context.drawImage(bitmap, 0, 0, w, h)
        bitmap.close()
        val q = quality.coerceIn(1, 100) / 100.0
        val options: dynamic = js("({ type: 'image/jpeg', quality: q })")
        val jpeg: dynamic = (canvas.convertToBlob(options) as Promise<dynamic>).await()
        val buffer: dynamic = (jpeg.arrayBuffer() as Promise<dynamic>).await()
        js("new Int8Array(buffer)").unsafeCast<ByteArray>()
    }.getOrNull()
}
