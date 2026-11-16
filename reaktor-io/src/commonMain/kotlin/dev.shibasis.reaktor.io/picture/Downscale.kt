package dev.shibasis.reaktor.io.picture

/**
 * A picture made small enough to keep: no side longer than [maxSide] pixels, the right way up, as
 * a JPEG at [quality] (1 to 100). A phone's photo of a receipt goes from four megabytes to a
 * hundred or two kilobytes and still reads.
 *
 * - Never made larger: a picture already within [maxSide] is only re-encoded.
 * - Turned upright from its EXIF note, so the result carries no note and shows the same everywhere.
 * - What was transparent becomes white, since a JPEG has no transparency.
 * - Null when the bytes aren't a picture this platform can decode, or it is too large to decode
 *   safely ([MAX_DOWNSCALE_PIXELS]). Never throws.
 *
 * Each platform uses its own decoder (Bitmap on Android, UIImage on iOS, ImageIO on the desktop,
 * a canvas in the browser), so the bytes differ from one to the next; the size and the way up
 * don't. iOS also takes HEIC, which is what an iPhone's camera writes.
 */
expect suspend fun downscalePicture(bytes: ByteArray, maxSide: Int = 1600, quality: Int = 80): ByteArray?

/**
 * The most pixels a picture may have to be decoded for downscaling: 120 million, well past any
 * phone camera (a 50 MP photo is 50 million) and well short of what would exhaust memory.
 */
const val MAX_DOWNSCALE_PIXELS = 120_000_000L
