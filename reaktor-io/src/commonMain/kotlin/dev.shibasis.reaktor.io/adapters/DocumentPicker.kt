package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/** A file the person chose, read whole while the platform still allowed reading it. */
class PickedDocument(val name: String, val bytes: ByteArray)

/**
 * The system's own file chooser, as one suspend call.
 *
 * Choosing a document is the inbound sibling of [ShareAdapter]: every platform has its own file UI
 * and nothing shared underneath it, so it is a slot the platform layer fills. GymBuddy and Lector
 * each wrote one in app code before this existed.
 *
 * The bytes are read before [pick] returns, because the permission to read a chosen file is only
 * certain while the chooser's callback runs — a path or URI handed back for later can stop opening.
 *
 * Bound to the screen that presents it: on Android it must be built while the Activity is being
 * created, and a new Activity installs a new one.
 */
interface DocumentPicker {
    /**
     * Shows the chooser filtered to [mimeTypes] (a wildcard such as `*`/`*` allowed). Null when the
     * person backed out or the file could not be read. One at a time: a second call while the
     * chooser is open returns null.
     */
    suspend fun pick(mimeTypes: List<String>): PickedDocument?
}

var Feature.DocumentPicker by CreateSlot<DocumentPicker>()
