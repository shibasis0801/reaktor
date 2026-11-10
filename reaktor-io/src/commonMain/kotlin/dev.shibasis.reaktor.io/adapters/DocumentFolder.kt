package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.serialization.Serializable

/** A folder the person chose, remembered: [id] is the platform's handle, [name] what to show. */
@Serializable
data class ChosenFolder(val id: String, val name: String)

/**
 * The system's folder chooser. The outbound sibling of [DocumentPicker]: where that reads one file
 * the person picks, this lets them pick a folder once, so an app can write into it again later
 * (a weekly backup) without asking each time. The permission to the folder is kept across restarts.
 *
 * Bound to the screen that presents it, like [DocumentPicker]: built while the Activity is created.
 */
interface FolderChooser {
    /** Shows the chooser; null when the person backed out or the folder can't be kept. */
    suspend fun choose(): ChosenFolder?
}

/**
 * Writes into a folder chosen earlier, from anywhere in the app, with or without a screen: a weekly
 * backup runs when the process happens to be alive, often with no Activity at all.
 */
interface FolderWriter {
    /** Writes [bytes] as [fileName], replacing a file of that name. False when the folder is gone or no longer allowed. */
    suspend fun write(folder: ChosenFolder, fileName: String, mimeType: String, bytes: ByteArray): Boolean

    /** The names of the files in [folder], or null when it can't be read. */
    suspend fun list(folder: ChosenFolder): List<String>?

    suspend fun delete(folder: ChosenFolder, fileName: String): Boolean
}

var Feature.FolderChooser by CreateSlot<FolderChooser>()
var Feature.FolderWriter by CreateSlot<FolderWriter>()
