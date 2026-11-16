package dev.shibasis.reaktor.io.adapters

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSClassFromString
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * [PhotoPicker] and [CameraCapture] on iOS, presented over whatever is frontmost.
 *
 * Choosing uses `PHPickerViewController`, which runs outside the app and needs no permission
 * (`UIImagePickerController`'s library on iOS 13, which doesn't either). Taking one uses the
 * camera sheet, which needs `NSCameraUsageDescription` in the app's Info.plist.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DarwinPhotos : PhotoPicker, CameraCapture {
    private var pending: CompletableDeferred<PickedPhoto?>? = null

    // UIKit holds its delegates weakly; this keeps the one in use alive while its sheet is up.
    private var delegate: NSObject? = null

    override suspend fun pick(): PickedPhoto? = present {
        if (NSClassFromString("PHPickerViewController") != null) {
            val configuration = PHPickerConfiguration().apply {
                filter = PHPickerFilter.imagesFilter
                selectionLimit = 1
            }
            val callbacks = ChooserDelegate(::finish)
            delegate = callbacks
            PHPickerViewController(configuration).apply { this.delegate = callbacks }
        } else {
            sheet(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary)
        }
    }

    override val available: Boolean
        get() = UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

    override suspend fun capture(): PickedPhoto? {
        if (!available) return null
        return present { sheet(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera) }
    }

    private fun sheet(source: UIImagePickerControllerSourceType): UIViewController {
        val callbacks = SheetDelegate(::finish)
        delegate = callbacks
        return UIImagePickerController().apply {
            sourceType = source
            this.delegate = callbacks
        }
    }

    private suspend fun present(make: () -> UIViewController): PickedPhoto? {
        if (pending != null) return null
        val result = CompletableDeferred<PickedPhoto?>()
        pending = result
        val presented = withContext(Dispatchers.Main) {
            val top = topViewController() ?: return@withContext false
            top.presentViewController(make(), animated = true, completion = null)
            true
        }
        if (!presented) finish(null)
        return result.await()
    }

    private fun finish(photo: PickedPhoto?) {
        delegate = null
        pending?.complete(photo)
        pending = null
    }

    /** The modern chooser: it stays up until dismissed, and hands back a provider to load from. */
    private class ChooserDelegate(private val done: (PickedPhoto?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val provider: NSItemProvider? = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
            if (provider == null) {
                done(null)
                return
            }
            // The file as it is in the library (HEIC or JPEG), on a queue of the system's own.
            provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                val photo = data?.let(::bytes)?.takeIf { it.isNotEmpty() }?.let(::PickedPhoto)
                dispatch_async(dispatch_get_main_queue()) { done(photo) }
            }
        }
    }

    /** The camera, and the library before iOS 14. */
    private class SheetDelegate(private val done: (PickedPhoto?) -> Unit) :
        NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
            // The camera hands over pixels, not a file: written out once here, near lossless, for
            // the caller to bring down to the size it keeps.
            done(image?.let { UIImageJPEGRepresentation(it, 0.92) }?.let(::bytes)?.takeIf { it.isNotEmpty() }?.let(::PickedPhoto))
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, completion = null)
            done(null)
        }
    }

    private companion object {
        fun bytes(data: NSData): ByteArray {
            val out = ByteArray(data.length.toInt())
            if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            return out
        }

        fun topViewController(): UIViewController? {
            var top = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            return top
        }
    }
}
