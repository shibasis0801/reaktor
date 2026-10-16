package dev.shibasis.reaktor.media

import dev.shibasis.reaktor.core.util.toByteArray
import dev.shibasis.reaktor.media.gallery.MediaPick
import dev.shibasis.reaktor.media.gallery.PHOTO_EDGE
import dev.shibasis.reaktor.media.gallery.PHOTO_QUALITY
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.floor

@OptIn(ExperimentalForeignApi::class)
internal fun photo(data: NSData, name: String?): MediaPick? {
    val image = UIImage.imageWithData(data) ?: return null
    val (width, height) = image.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null
    val scale = minOf(1.0, PHOTO_EDGE / maxOf(width, height))
    val wide = floor(width * scale).coerceAtLeast(1.0)
    val tall = floor(height * scale).coerceAtLeast(1.0)
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
    val drawn = UIGraphicsImageRenderer(CGSizeMake(wide, tall), format).imageWithActions { _ ->
        image.drawInRect(CGRectMake(0.0, 0.0, wide, tall))
    }
    val jpeg = UIImageJPEGRepresentation(drawn, PHOTO_QUALITY / 100.0) ?: return null
    return MediaPick(jpeg.toByteArray(), "image/jpeg", name?.substringBeforeLast('.')?.plus(".jpg"), width = wide.toInt(), height = tall.toInt())
}
