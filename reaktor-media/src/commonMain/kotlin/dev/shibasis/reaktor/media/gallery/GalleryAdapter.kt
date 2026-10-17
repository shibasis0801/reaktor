package dev.shibasis.reaktor.media.gallery

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

data class MediaPick(
    val bytes: ByteArray,
    val mimeType: String,
    val suggestedName: String? = null,
    val durationMillis: Long? = null,
    val thumbnail: ByteArray? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = bytes.size.toLong(),
)

const val PHOTO_EDGE = 2048
const val PHOTO_QUALITY = 85

internal const val THUMBNAIL_EDGE = 480
internal const val THUMBNAIL_QUALITY = 80

abstract class GalleryAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    var readLimit: Long = Long.MAX_VALUE

    abstract suspend fun pickImage(): MediaPick?

    open suspend fun pickVideo(): MediaPick? = null
}

var Feature.Gallery by CreateSlot<GalleryAdapter<*>>()
