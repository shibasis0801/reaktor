package dev.shibasis.reaktor.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.media.gallery.MediaPick
import dev.shibasis.reaktor.media.gallery.PHOTO_EDGE
import dev.shibasis.reaktor.media.gallery.PHOTO_QUALITY
import java.io.ByteArrayInputStream
import dev.shibasis.reaktor.media.gallery.THUMBNAIL_EDGE
import dev.shibasis.reaktor.media.gallery.THUMBNAIL_QUALITY
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun ContentResolver.bytes(uri: Uri): ByteArray? = openInputStream(uri)?.use { it.readBytes() }

internal fun ContentResolver.size(uri: Uri): Long? = runCatching {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

internal fun ContentResolver.pick(uri: Uri, mimeType: String, name: String?, limit: Long): MediaPick? {
    val size = size(uri)
    if (size != null && size > limit) return MediaPick(ByteArray(0), mimeType, name, size = size)
    return bytes(uri)?.let { MediaPick(it, mimeType, name) }
}

internal fun photo(bytes: ByteArray, mimeType: String, name: String?): MediaPick = if (mimeType == "image/gif") MediaPick(bytes, mimeType, name) else runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    var sample = 1
    while (longest / (sample * 2) >= PHOTO_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
    val turn = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
        else -> 0f
    }
    val scale = minOf(1f, PHOTO_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(turn)
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val jpeg = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }.toByteArray()
    val picked = MediaPick(jpeg, "image/jpeg", name?.substringBeforeLast('.')?.plus(".jpg"), width = upright.width, height = upright.height)
    if (upright !== decoded) upright.recycle()
    decoded.recycle()
    picked
}.onFailure { Logger.w(it) { "Photo could not be prepared" } }.getOrNull() ?: MediaPick(bytes, mimeType, name)

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
