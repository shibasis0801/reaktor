package dev.shibasis.reaktor.media.files

import dev.shibasis.reaktor.media.durationMillis
import dev.shibasis.reaktor.media.gallery.MediaPick
import dev.shibasis.reaktor.media.readBytes
import dev.shibasis.reaktor.media.remove
import dev.shibasis.reaktor.media.topViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVURLAsset
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.UniformTypeIdentifiers.UTTypeText
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

class DarwinFilePickerAdapter(
    private val viewControllerProvider: () -> UIViewController? = { topViewController() },
) : FilePickerAdapter<Unit>(Unit) {

    private var pickerDelegate: UIDocumentPickerDelegateProtocol? = null

    override suspend fun pick(mimeTypes: List<String>): MediaPick? {
        val url = withContext(Dispatchers.Main) { present(contentTypes(mimeTypes)) } ?: return null
        return try {
            withContext(Dispatchers.IO) { filePick(url) }
        } finally {
            url.remove()
        }
    }

    private suspend fun present(types: List<UTType>): NSURL? = suspendCancellableCoroutine { cont ->
        val presenter = viewControllerProvider()
        if (presenter == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        val finish: (NSURL?) -> Unit = { url ->
            pickerDelegate = null
            if (cont.isActive) cont.resume(url) else url?.remove()
        }
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                finish(null)
            }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)

        cont.invokeOnCancellation {
            dispatch_async(dispatch_get_main_queue()) {
                pickerDelegate = null
                picker.dismissViewControllerAnimated(true, completion = null)
            }
        }
    }
}

private fun contentTypes(mimeTypes: List<String>): List<UTType> =
    mimeTypes.map(::contentType).distinct().ifEmpty { listOf(UTTypeItem) }

private fun contentType(mimeType: String): UTType = when {
    mimeType == "audio/*" -> UTTypeAudio
    mimeType == "video/*" -> UTTypeMovie
    mimeType == "image/*" -> UTTypeImage
    mimeType == "text/*" -> UTTypeText
    mimeType.endsWith("/*") -> UTTypeItem
    else -> UTType.typeWithMIMEType(mimeType) ?: UTTypeItem
}

private fun filePick(url: NSURL): MediaPick? {
    val bytes = url.readBytes() ?: return null
    val mimeType = url.pathExtension
        ?.takeIf { it.isNotEmpty() }
        ?.let { UTType.typeWithFilenameExtension(it)?.preferredMIMEType }
        ?: "application/octet-stream"
    val timed = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
    return MediaPick(
        bytes = bytes,
        mimeType = mimeType,
        suggestedName = url.lastPathComponent,
        durationMillis = if (timed) AVURLAsset(uRL = url, options = null).durationMillis() else null,
    )
}
