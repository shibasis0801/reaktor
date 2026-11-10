package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * The system's own save dialog: the person chooses where one file goes and what it is called. The
 * outbound sibling of [DocumentPicker], and unlike [ShareAdapter] it ends in a file the person can
 * find again, not in whatever app the share sheet offered.
 *
 * Bound to the screen that presents it, like [DocumentPicker]: built while the Activity is created.
 */
interface DocumentSaver {
    /**
     * Shows the dialog with [fileName] filled in and writes [bytes] where the person chose. Returns
     * the saved file's name (the person may have changed it), or null when they backed out or the
     * write failed. One at a time: a second call while the dialog is open returns null.
     */
    suspend fun save(fileName: String, mimeType: String, bytes: ByteArray): String?
}

var Feature.DocumentSaver by CreateSlot<DocumentSaver>()
