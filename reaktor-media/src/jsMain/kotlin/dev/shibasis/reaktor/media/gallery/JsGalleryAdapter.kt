package dev.shibasis.reaktor.media.gallery

import dev.shibasis.reaktor.media.pickFile

class JsGalleryAdapter : GalleryAdapter<Unit>(Unit) {
    override suspend fun pickImage(): MediaPick? = pickFile(accept = "image/*", fallbackMimeType = "image/*")

    override suspend fun pickVideo(): MediaPick? = pickFile(accept = "video/*", fallbackMimeType = "video/mp4")
}
