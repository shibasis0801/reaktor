package dev.shibasis.reaktor.media.files

import dev.shibasis.reaktor.media.gallery.MediaPick
import dev.shibasis.reaktor.media.pickFile

class JsFilePickerAdapter : FilePickerAdapter<Unit>(Unit) {
    override suspend fun pick(mimeTypes: List<String>): MediaPick? =
        pickFile(accept = mimeTypes.joinToString(","), fallbackMimeType = "application/octet-stream")
}
