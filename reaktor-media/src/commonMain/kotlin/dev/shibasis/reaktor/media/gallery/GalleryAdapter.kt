package dev.shibasis.reaktor.media.gallery

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

data class MediaPick(
    val bytes: ByteArray,
    val mimeType: String,
    val suggestedName: String? = null,
)

abstract class GalleryAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {
    abstract suspend fun pickImage(): MediaPick?
}

var Feature.Gallery by CreateSlot<GalleryAdapter<*>>()
