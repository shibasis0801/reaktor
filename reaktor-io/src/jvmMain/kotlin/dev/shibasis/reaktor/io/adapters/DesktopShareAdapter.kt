package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * The desktop's version of sharing a file out, which is saving it somewhere the person can find.
 *
 * A desktop has no share sheet. What it has is a Downloads folder and a file manager, and a file
 * that has been put in the first and shown in the second has been handed over as completely as a
 * desktop can hand anything over.
 *
 * The file is shown, never opened. What gets passed to an adapter like this is often something
 * another device or another person sent, and opening it with whatever the system associates with
 * its extension is running it if it happens to be a script or an application. Where it is and what
 * it is called is enough; the person decides the rest.
 *
 * @param directory where files are put. The user's Downloads folder unless told otherwise.
 * @param reveal how a saved file is shown. Replaced in tests, which should not open windows.
 * @param onSaved told where each file went, so an app can say so.
 */
class DesktopShareAdapter(
    private val directory: File = File(System.getProperty("user.home"), "Downloads"),
    private val reveal: (File) -> Unit = ::showInFileManager,
    private val onSaved: (File) -> Unit = {},
) : ShareAdapter<Unit>(Unit) {

    override suspend fun shareFile(payload: SharePayload): Boolean = withContext(Dispatchers.IO) {
        val saved = runCatching {
            directory.mkdirs()
            unusedIn(directory, safeFileName(payload.fileName)).also { it.writeBytes(payload.bytes) }
        }.getOrNull() ?: return@withContext false

        onSaved(saved)
        // Showing it is a courtesy. The file is saved whether or not the file manager cooperates.
        runCatching { reveal(saved) }
        true
    }

    /** The clipboard: on a desktop, text that is to go somewhere else gets there by being pasted. */
    override suspend fun shareText(text: String, title: String?, subject: String?): Boolean =
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }.isSuccess

    companion object {
        /**
         * A file in [directory] called [name], or the first `name (2)`, `name (3)` that is free.
         *
         * Never the one that is already there: the second thing called `plan.pdf` is not a new
         * version of the first, and replacing a file in somebody's Downloads is not this class's
         * decision to make.
         */
        fun unusedIn(directory: File, name: String): File {
            val first = File(directory, name)
            if (!first.exists()) return first

            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var count = 2
            while (true) {
                val candidate = File(directory, "$stem ($count)$extension")
                if (!candidate.exists()) return candidate
                count++
            }
        }
    }
}

/** Opens the folder with the file selected where the platform can do that, and the folder where not. */
private fun showInFileManager(file: File) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
        desktop.browseFileDirectory(file)
    } else if (desktop.isSupported(Desktop.Action.OPEN)) {
        desktop.open(file.parentFile)
    }
}
