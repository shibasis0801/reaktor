package dev.shibasis.reaktor.media

import dev.shibasis.reaktor.core.util.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import kotlin.math.roundToLong

internal fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.keyWindow
        ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

internal fun temporaryUrl(prefix: String, extension: String): NSURL =
    NSURL.fileURLWithPath(NSTemporaryDirectory() + "$prefix-${NSUUID().UUIDString}.$extension")

internal fun NSURL.copyToTemporary(prefix: String, fallbackExtension: String): NSURL? {
    val target = temporaryUrl(prefix, pathExtension.orEmpty().ifEmpty { fallbackExtension })
    return target.takeIf { NSFileManager.defaultManager.copyItemAtURL(this, toURL = it, error = null) }
}

internal fun NSURL.remove() {
    NSFileManager.defaultManager.removeItemAtURL(this, null)
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSURL.readBytes(): ByteArray? =
    NSData.dataWithContentsOfURL(this, options = NSDataReadingMappedIfSafe, error = null)?.toByteArray()

@OptIn(ExperimentalForeignApi::class)
internal fun AVAsset.durationMillis(): Long? =
    CMTimeGetSeconds(duration).takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToLong() }
