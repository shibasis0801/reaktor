package dev.shibasis.reaktor.io.adapters

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController

/**
 * Shares through `UIActivityViewController`.
 *
 * A file rather than the raw data, deliberately. Handing `NSData` to the activity controller works,
 * but the receiving app then gets an unnamed blob: Mail attaches it as "Attachment-1", Files offers
 * no sensible name, and a backup the user meant to keep becomes unfindable. Writing to a temporary
 * file first means the name travels with it.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinShareAdapter : ShareAdapter<Unit>(Unit) {

    override suspend fun shareFile(payload: SharePayload): Boolean {
        val url = withContext(Dispatchers.Default) { write(payload) } ?: return false

        return withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext false
            val controller = UIActivityViewController(listOf(url), null)

            // An iPad refuses to present a sheet with nowhere to point it, and the refusal is a
            // crash rather than a no-op. Anchoring to the presenter's own view is the safe default.
            controller.popoverPresentationController?.sourceView = presenter.view

            presenter.presentViewController(controller, animated = true, completion = null)
            true
        }
    }

    override suspend fun shareText(text: String, title: String?, subject: String?): Boolean =
        withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext false
            val controller = UIActivityViewController(listOf(text), null)
            controller.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(controller, animated = true, completion = null)
            true
        }

    private fun write(payload: SharePayload): NSURL? {
        val path = NSTemporaryDirectory() + payload.fileName
        val data = payload.bytes.toNSData()
        return if (data.writeToFile(path, atomically = true)) NSURL.fileURLWithPath(path) else null
    }

    /**
     * The controller actually on screen.
     *
     * Presenting from the root while something else is already presented is silently ignored by
     * UIKit, so the chain has to be walked to the end — otherwise the share sheet never appears
     * for a user who opened it from a sheet of the app's own.
     */
    private fun topViewController(): UIViewController? {
        val root = UIApplication.sharedApplication.windows
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }
            ?.rootViewController
            ?: return null

        var top = root
        while (true) top = top.presentedViewController ?: return top
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) {
            NSData()
        } else {
            usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
        }
}
