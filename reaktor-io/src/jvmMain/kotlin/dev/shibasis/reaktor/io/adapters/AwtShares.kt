package dev.shibasis.reaktor.io.adapters

import java.awt.Image
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The two ways into an AWT app: a drag onto the window, and a paste.
 *
 * Extensions rather than part of [DesktopShareReceiver] so that the adapter itself stays free of a
 * toolkit — an app on SWT, or a headless one, links none of this. Every Compose Desktop app is an
 * AWT app, so in practice this is the file that gets used.
 *
 * Both mechanisms hand over the same `Transferable`, which is why one conversion serves both.
 */
fun DesktopShareReceiver.acceptDrops(window: Window) {
    window.dropTarget = DropTarget().apply {
        defaultActions = DnDConstants.ACTION_COPY

        addDropTargetListener(object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val share = runCatching { event.transferable.toShare() }.getOrNull()
                // dropComplete tells the source whether to release the data. Saying false on a drop
                // that was in fact read leaves some applications showing a failed transfer.
                event.dropComplete(share != null && offer(share))
            }
        })
    }
}

/**
 * Emits whatever is on the system clipboard, if anything shareable is.
 *
 * **Deliberately not bound to a global key hook.** Compose renders into a single AWT canvas, so an
 * AWT-level listener cannot tell that the user is typing in a text field, and would turn every ⌘V
 * into a duplicate drop beside whatever was pasted. Bind it in the app, where Compose's own focus
 * handling gets the event first:
 *
 * ```
 * Modifier.onKeyEvent { event -> if (event.isPaste) receiver.paste() else false }
 * ```
 *
 * A focused text field consumes the shortcut before it reaches the root — which is what a user
 * expects, and is not something this function could arrange on its own.
 *
 * Returns false for an empty or unreadable clipboard (another process can hold it), so a key
 * binding can leave the event unhandled rather than swallowing it.
 */
fun DesktopShareReceiver.paste(): Boolean {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
        ?: return false
    val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: return false
    val share = runCatching { contents.toShare() }.getOrNull() ?: return false

    return offer(share)
}

/**
 * One transfer, whichever way it arrived.
 *
 * Files win over text when both are offered, because a file manager and most editors put a path on
 * the clipboard *as well as* the file itself — taking the text there would drop a string that reads
 * like a path instead of the document the user copied.
 */
internal fun Transferable.toShare(): ReceivedShare? {
    files()?.let { files ->
        return ReceivedShare(
            // A single file gets a real type; a mixed selection has no one type to give.
            mime = files.singleOrNull()?.probeMime() ?: "*/*",
            fileUris = files.map { it.absolutePath },
            title = files.singleOrNull()?.name,
        )
    }

    image()?.let { staged ->
        return ReceivedShare(
            mime = "image/png",
            fileUris = listOf(staged.absolutePath),
            title = staged.name,
        )
    }

    text()?.let { text ->
        return ReceivedShare(mime = "text/plain", text = text)
    }

    return null
}

@Suppress("UNCHECKED_CAST")
private fun Transferable.files(): List<File>? {
    if (!isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null

    val files = runCatching {
        getTransferData(DataFlavor.javaFileListFlavor) as List<File>
    }.getOrNull().orEmpty()

    return files.filter { it.isFile }.takeIf { it.isNotEmpty() }
}

private fun Transferable.text(): String? {
    if (!isDataFlavorSupported(DataFlavor.stringFlavor)) return null

    return runCatching { getTransferData(DataFlavor.stringFlavor) as String }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * A screenshot on the clipboard, written where it can be read back.
 *
 * Pasted image data has no path, and [ReceivedShare] carries handles rather than bytes — so this is
 * the one case that stages a file. It goes to the JVM's temp directory and is marked for deletion
 * at exit: a paste the app never resolves costs one file until the process ends.
 */
private fun Transferable.image(): File? {
    if (!isDataFlavorSupported(DataFlavor.imageFlavor)) return null

    val image = runCatching { getTransferData(DataFlavor.imageFlavor) as Image }.getOrNull()
        ?: return null

    return runCatching {
        val file = File.createTempFile("reaktor-share-", ".png").apply { deleteOnExit() }
        ImageIO.write(image.toBuffered(), "png", file)
        file
    }.getOrNull()
}

private fun Image.toBuffered(): BufferedImage {
    if (this is BufferedImage) return this

    // ARGB rather than RGB: a screenshot of a rounded window has transparent corners, and
    // flattening them here would paint them black.
    val buffered = BufferedImage(
        getWidth(null).coerceAtLeast(1),
        getHeight(null).coerceAtLeast(1),
        BufferedImage.TYPE_INT_ARGB,
    )
    val canvas = buffered.createGraphics()
    try {
        canvas.drawImage(this, 0, 0, null)
    } finally {
        canvas.dispose()
    }
    return buffered
}
