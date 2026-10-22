package dev.shibasis.reaktor.io.adapters

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * [DocumentPicker] on `UIDocumentPickerViewController`, opened as a copy so no security scope has to
 * be held, presented over whatever is frontmost.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DarwinDocumentPicker : DocumentPicker {
    private var pending: CompletableDeferred<PickedDocument?>? = null

    // UIKit holds its delegate weakly; this keeps it alive while the chooser is on screen.
    private var delegate: Delegate? = null

    override suspend fun pick(mimeTypes: List<String>): PickedDocument? {
        if (pending != null) return null
        val result = CompletableDeferred<PickedDocument?>()
        pending = result
        val presented = withContext(Dispatchers.Main) {
            val types = mimeTypes.mapNotNull { type ->
                if (type == "*/*") UTTypeData else UTType.typeWithMIMEType(type)
            }.ifEmpty { listOf(UTTypeData) }
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
            val callbacks = Delegate { document -> finish(document) }
            delegate = callbacks
            picker.delegate = callbacks
            val top = topViewController() ?: return@withContext false
            top.presentViewController(picker, animated = true, completion = null)
            true
        }
        if (!presented) finish(null)
        return result.await()
    }

    private fun finish(document: PickedDocument?) {
        delegate = null
        pending?.complete(document)
        pending = null
    }

    private class Delegate(private val done: (PickedDocument?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            done(url?.let(::read))
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = done(null)
    }

    private companion object {
        fun read(url: NSURL): PickedDocument? {
            val data = NSData.dataWithContentsOfURL(url) ?: return null
            val bytes = ByteArray(data.length.toInt())
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            return PickedDocument(url.lastPathComponent ?: "document", bytes)
        }

        fun topViewController(): UIViewController? {
            var top = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            return top
        }
    }
}
