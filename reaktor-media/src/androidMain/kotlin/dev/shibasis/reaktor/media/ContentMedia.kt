package dev.shibasis.reaktor.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.media.gallery.MediaPick
import dev.shibasis.reaktor.media.gallery.THUMBNAIL_EDGE
import dev.shibasis.reaktor.media.gallery.THUMBNAIL_QUALITY
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun ContentResolver.bytes(uri: Uri): ByteArray? = openInputStream(uri)?.use { it.readBytes() }

internal fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

internal fun Context.durationMillis(uri: Uri): Long? = retrieve(uri) { durationMillis() }

internal fun MediaPick.withVideoDetails(context: Context, uri: Uri): MediaPick = context.retrieve(uri) {
    val width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    val height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    val upright = (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0) % 180 == 0
    copy(
        durationMillis = durationMillis(),
        thumbnail = thumbnail(),
        width = if (upright) width else height,
        height = if (upright) height else width,
    )
} ?: this

private fun <T> Context.retrieve(uri: Uri, read: MediaMetadataRetriever.() -> T): T? {
    val retriever = MediaMetadataRetriever()
    return try {
        runCatching {
            retriever.setDataSource(this, uri)
            retriever.read()
        }.onFailure { Logger.w(it) { "Media metadata unavailable" } }.getOrNull()
    } finally {
        runCatching { retriever.release() }
    }
}

private fun MediaMetadataRetriever.number(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

private fun MediaMetadataRetriever.durationMillis(): Long? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

private fun MediaMetadataRetriever.thumbnail(): ByteArray? {
    val frame = getFrameAtTime(0) ?: return null
    val scale = THUMBNAIL_EDGE.toFloat() / maxOf(frame.width, frame.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).roundToInt().coerceAtLeast(1),
            (frame.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        frame
    }
    val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it) }.toByteArray()
    if (scaled !== frame) scaled.recycle()
    frame.recycle()
    return jpeg
}
