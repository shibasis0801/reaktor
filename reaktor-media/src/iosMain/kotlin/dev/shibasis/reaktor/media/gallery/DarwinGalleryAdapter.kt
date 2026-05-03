package dev.shibasis.reaktor.media.gallery

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

class DarwinGalleryAdapter(
    private val viewControllerProvider: () -> UIViewController? = { topViewController() },
) : GalleryAdapter<Unit>(Unit) {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun pickImage(): MediaPick? = suspendCancellableCoroutine { cont ->
        val presenter = viewControllerProvider()
        if (presenter == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val config = PHPickerConfiguration().apply {
            setSelectionLimit(1)
            setFilter(PHPickerFilter.imagesFilter())
        }
        val picker = PHPickerViewController(configuration = config)

        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                val result = didFinishPicking.firstOrNull() as? PHPickerResult
                if (result == null) {
                    if (cont.isActive) cont.resume(null)
                    return
                }
                val provider = result.itemProvider
                val typeId = "public.image"
                provider.loadDataRepresentationForTypeIdentifier(typeId) { data: NSData?, _: NSError? ->
                    if (data == null) {
                        if (cont.isActive) cont.resume(null)
                        return@loadDataRepresentationForTypeIdentifier
                    }
                    val length = data.length.toInt()
                    val bytes = ByteArray(length)
                    if (length > 0) {
                        bytes.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), data.bytes, length.toULong())
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(
                            MediaPick(
                                bytes = bytes,
                                mimeType = "image/*",
                                suggestedName = provider.suggestedName,
                            ),
                        )
                    }
                }
            }
        }
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)

        cont.invokeOnCancellation {
            picker.dismissViewControllerAnimated(true, completion = null)
        }
    }
}

private fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.keyWindow
        ?: UIApplication.sharedApplication.windows.firstOrNull() as? platform.UIKit.UIWindow
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
