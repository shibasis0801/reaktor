package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * The desktop's version of a share sheet, which is that there isn't one.
 *
 * A desktop has no OS-level "share into this app" channel. What it has is drag-and-drop, paste,
 * and files opened with the app — three different mechanisms that produce the same thing. So the
 * adapter is the same [ShareReceiver] and the window pushes into it, rather than the adapter
 * reaching into the toolkit.
 *
 * That inversion is deliberate: the drop target belongs to whatever window framework the app
 * happens to use — Swing, SWT, Compose — and `reaktor-io` should not depend on any of them.
 *
 * ```
 * val receiver = DesktopShareReceiver()
 * Feature.ShareReceiver = receiver
 * window.dropTarget = DropTarget(/* … */ { files -> receiver.offerFiles(files) })
 * ```
 *
 * An app on AWT — which is every Compose Desktop app — does not have to write that itself:
 * [acceptDrops] and [paste] in `AwtShares.kt` do it, and an app on something else never links them.
 */
class DesktopShareReceiver : ShareReceiver<Unit>(Unit) {

    private val shares = MutableSharedFlow<ReceivedShare>(replay = 1, extraBufferCapacity = 16)

    override val incoming: Flow<ReceivedShare> = shares.asSharedFlow()

    fun offer(share: ReceivedShare): Boolean = shares.tryEmit(share)

    fun offerText(text: String, mime: String = "text/plain"): Boolean =
        offer(ReceivedShare(mime = mime, text = text))

    fun offerFiles(paths: List<String>, mime: String = "application/octet-stream"): Boolean =
        offer(ReceivedShare(mime = mime, fileUris = paths))

    /**
     * Reads a path.
     *
     * A desktop share handle is an ordinary absolute path — there is no provider indirection to
     * resolve — so the only thing that can go wrong is that the file moved between the drop and
     * the read, which is why this is nullable rather than throwing.
     */
    override suspend fun read(fileUri: String): SharedFile? = withContext(Dispatchers.IO) {
        val file = File(fileUri)
        if (!file.isFile) return@withContext null

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null

        SharedFile(name = file.name, mime = file.probeMime(), bytes = bytes)
    }
}

/** The type the OS believes a file has, falling back to bytes rather than guessing from a name. */
internal fun File.probeMime(): String =
    runCatching { Files.probeContentType(toPath()) }.getOrNull() ?: "application/octet-stream"
