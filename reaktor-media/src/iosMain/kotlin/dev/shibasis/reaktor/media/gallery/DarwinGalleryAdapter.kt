package dev.shibasis.reaktor.media.gallery

import dev.shibasis.reaktor.core.util.toByteArray
import dev.shibasis.reaktor.media.copyToTemporary
import dev.shibasis.reaktor.media.durationMillis
import dev.shibasis.reaktor.media.readBytes
import dev.shibasis.reaktor.media.remove
import dev.shibasis.reaktor.media.temporaryUrl
import dev.shibasis.reaktor.media.topViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

class DarwinGalleryAdapter(
    private val viewControllerProvider: () -> UIViewController? = { topViewController() },
) : GalleryAdapter<Unit>(Unit) {

    private var pickerDelegate: PHPickerViewControllerDelegateProtocol? = null

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
                pickerDelegate = null
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
        pickerDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)

        cont.invokeOnCancellation {
            picker.dismissViewControllerAnimated(true, completion = null)
        }
    }

    override suspend fun pickVideo(): MediaPick? {
        val movie = withContext(Dispatchers.Main) { pickMovie() } ?: return null
        val exported = temporaryUrl("reaktor-export", "mp4")
        return try {
            withContext(Dispatchers.IO) {
                if (transcode(movie.url, exported)) {
                    moviePick(exported, "video/mp4", "${movie.name}.mp4")
                } else {
                    val extension = movie.url.pathExtension.orEmpty()
                    val mimeType = UTType.typeWithFilenameExtension(extension)?.preferredMIMEType ?: "video/quicktime"
                    moviePick(movie.url, mimeType, "${movie.name}.$extension")
                }
            }
        } finally {
            movie.url.remove()
            exported.remove()
        }
    }

    private suspend fun pickMovie(): PickedMovie? = suspendCancellableCoroutine { cont ->
        val presenter = viewControllerProvider()
        if (presenter == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val config = PHPickerConfiguration().apply {
            setSelectionLimit(1)
            setFilter(PHPickerFilter.videosFilter())
        }
        val picker = PHPickerViewController(configuration = config)

        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                pickerDelegate = null
                val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
                if (provider == null) {
                    if (cont.isActive) cont.resume(null)
                    return
                }
                provider.loadFileRepresentationForTypeIdentifier("public.movie") { url: NSURL?, _: NSError? ->
                    val copy = url?.copyToTemporary("reaktor-pick", "mov")
                    if (copy != null && cont.isActive) {
                        cont.resume(PickedMovie(copy, provider.suggestedName ?: "video"))
                    } else {
                        copy?.remove()
                        if (cont.isActive) cont.resume(null)
                    }
                }
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

private class PickedMovie(val url: NSURL, val name: String)

private suspend fun transcode(source: NSURL, target: NSURL): Boolean {
    val session = AVAssetExportSession.exportSessionWithAsset(
        AVURLAsset(uRL = source, options = null),
        AVAssetExportPresetMediumQuality,
    ) ?: return false
    session.outputURL = target
    session.outputFileType = AVFileTypeMPEG4
    session.shouldOptimizeForNetworkUse = true
    return suspendCancellableCoroutine { cont ->
        session.exportAsynchronouslyWithCompletionHandler {
            if (cont.isActive) cont.resume(session.status == AVAssetExportSessionStatusCompleted)
        }
        cont.invokeOnCancellation { session.cancelExport() }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun moviePick(url: NSURL, mimeType: String, name: String): MediaPick? {
    val bytes = url.readBytes()?.takeIf { it.isNotEmpty() } ?: return null
    val asset = AVURLAsset(uRL = url, options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
    val size = track?.let { CGSizeApplyAffineTransform(it.naturalSize, it.preferredTransform) }
    return MediaPick(
        bytes = bytes,
        mimeType = mimeType,
        suggestedName = name,
        durationMillis = asset.durationMillis(),
        thumbnail = asset.thumbnail(),
        width = size?.useContents { abs(width).roundToInt() },
        height = size?.useContents { abs(height).roundToInt() },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun AVAsset.thumbnail(): ByteArray? {
    val generator = AVAssetImageGenerator(asset = this).apply {
        appliesPreferredTrackTransform = true
        maximumSize = CGSizeMake(THUMBNAIL_EDGE.toDouble(), THUMBNAIL_EDGE.toDouble())
    }
    val frame = generator.copyCGImageAtTime(CMTimeMake(value = 0, timescale = 1), actualTime = null, error = null) ?: return null
    val jpeg = UIImageJPEGRepresentation(UIImage(cGImage = frame), THUMBNAIL_QUALITY / 100.0)
    CGImageRelease(frame)
    return jpeg?.toByteArray()
}
