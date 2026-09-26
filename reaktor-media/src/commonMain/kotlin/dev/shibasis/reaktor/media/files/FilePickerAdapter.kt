package dev.shibasis.reaktor.media.files

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.media.gallery.MediaPick

abstract class FilePickerAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    abstract suspend fun pick(mimeTypes: List<String>): MediaPick?
}

var Feature.FilePicker by CreateSlot<FilePickerAdapter<*>>()
