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
)

internal const val THUMBNAIL_EDGE = 480
internal const val THUMBNAIL_QUALITY = 80

abstract class GalleryAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    abstract suspend fun pickImage(): MediaPick?

    open suspend fun pickVideo(): MediaPick? = null
}

var Feature.Gallery by CreateSlot<GalleryAdapter<*>>()
