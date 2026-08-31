package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 */
class DesktopShareReceiver : ShareReceiver<Unit>(Unit) {

    private val shares = MutableSharedFlow<ReceivedShare>(replay = 1, extraBufferCapacity = 16)

    override val incoming: Flow<ReceivedShare> = shares.asSharedFlow()

    fun offer(share: ReceivedShare): Boolean = shares.tryEmit(share)

    fun offerText(text: String, mime: String = "text/plain"): Boolean =
        offer(ReceivedShare(mime = mime, text = text))

    fun offerFiles(paths: List<String>, mime: String = "application/octet-stream"): Boolean =
        offer(ReceivedShare(mime = mime, fileUris = paths))
}
