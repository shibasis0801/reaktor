package dev.shibasis.reaktor.io.picture

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.round

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun downscalePicture(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? = withContext(Dispatchers.Default) {
    if (bytes.isEmpty()) return@withContext null
    // HEIC isn't a format the header reader knows; UIImage decodes it, and is asked its size below.
    pictureHeader(bytes)?.let { if (it.pixels > MAX_DOWNSCALE_PIXELS) return@withContext null }
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    val image = UIImage.imageWithData(data) ?: return@withContext null
    // A UIImage from data has a scale of 1, so its points are its pixels, already the right way up.
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0 || width * height > MAX_DOWNSCALE_PIXELS) return@withContext null
    val ratio = minOf(1.0, maxSide.coerceAtLeast(1) / max(width, height))
    val w = max(1.0, round(width * ratio))
    val h = max(1.0, round(height * ratio))

    // Opaque, on white: drawing applies the photo's orientation, and JPEG has no transparency.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, 1.0)
    UIColor.whiteColor.setFill()
    UIRectFill(CGRectMake(0.0, 0.0, w, h))
    image.drawInRect(CGRectMake(0.0, 0.0, w, h))
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    val jpeg = scaled?.let { UIImageJPEGRepresentation(it, quality.coerceIn(1, 100) / 100.0) } ?: return@withContext null
    ByteArray(jpeg.length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), jpeg.bytes, jpeg.length) }
    }
}
