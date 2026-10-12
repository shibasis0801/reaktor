package dev.shibasis.reaktor.io.picture

enum class PictureFormat(val mime: String) {
    Png("image/png"),
    Jpeg("image/jpeg"),
    Gif("image/gif"),
    Webp("image/webp"),
}

/**
 * What a picture says about itself before any of it is decoded.
 *
 * [width] and [height] are the picture the right way up — a phone photo is very often stored on its
 * side with a note saying which way to turn it, and the size anyone wants is the one they will see.
 */
data class PictureHeader(val format: PictureFormat, val width: Int, val height: Int) {
    /** How much memory drawing this costs, near enough: four bytes for each of these. */
    val pixels: Long get() = width.toLong() * height
}

/**
 * Reads the size of the picture in [bytes] from its first few, or null when these are not a
 * picture this recognises.
 *
 * Three things want this, and all of them want it before a decoder is involved:
 *
 * - **Whether to decode at all.** A decoder is handed bytes and allocates what the header asks for.
 *   A 20,000 × 20,000 PNG is a few kilobytes on disk and 1.6 GB as a bitmap, and where the decoder
 *   cannot subsample — Skia, so the desktop and the browser — asking it to draw one ends the
 *   process. When the bytes came from somewhere else and stay in a list, that is every launch.
 * - **How much room to leave.** A row that knows its picture is 4:3 can be the right height before
 *   the picture has loaded, and the list under it does not jump when it does.
 * - **Whether it is a picture.** A mime type that arrived with a file is a claim made by whoever
 *   sent it. This looks at the bytes.
 *
 * It reads headers and nothing else: PNG, JPEG, GIF and WebP, which is what cameras, screenshots
 * and browsers produce. SVG is deliberately not here — it is a document that draws, not a picture,
 * and a caller that wants to render one should have to decide that on purpose. Orientation is read
 * from JPEG only; the handful of WebP files that carry one are reported as stored.
 *
 * Never throws, whatever it is given.
 */
fun pictureHeader(bytes: ByteArray): PictureHeader? {
    val read = Reader(bytes)
    val header = png(read) ?: gif(read) ?: webp(read) ?: jpeg(read) ?: return null
    return header.takeIf { it.width > 0 && it.height > 0 }
}

/** Bytes, read without ever running off the end: anything past it is -1, and every caller checks. */
private class Reader(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun u8(at: Int): Int = if (at < 0 || at >= bytes.size) -1 else bytes[at].toInt() and 0xFF

    fun u16be(at: Int): Int = pair(u8(at), u8(at + 1))
    fun u16le(at: Int): Int = pair(u8(at + 1), u8(at))

    fun u24le(at: Int): Int {
        val low = u16le(at)
        val high = u8(at + 2)
        return if (low < 0 || high < 0) -1 else (high shl 16) or low
    }

    /** Negative for anything that will not fit a size — which a real picture's side always does. */
    fun u32be(at: Int): Int {
        val high = u16be(at)
        val low = u16be(at + 2)
        return if (high < 0 || low < 0 || high > 0x7FFF) -1 else (high shl 16) or low
    }

    fun u32le(at: Int): Int {
        val low = u16le(at)
        val high = u16le(at + 2)
        return if (high < 0 || low < 0 || high > 0x7FFF) -1 else (high shl 16) or low
    }

    fun says(at: Int, text: String): Boolean = text.indices.all { u8(at + it) == text[it].code }

    private fun pair(high: Int, low: Int): Int = if (high < 0 || low < 0) -1 else (high shl 8) or low
}

private fun png(read: Reader): PictureHeader? {
    val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (signature.indices.any { read.u8(it) != signature[it] }) return null
    // The first chunk is always the header, and a file where it is not is not one to draw.
    if (!read.says(12, "IHDR")) return null
    return PictureHeader(PictureFormat.Png, read.u32be(16), read.u32be(20))
}

private fun gif(read: Reader): PictureHeader? {
    if (!read.says(0, "GIF87a") && !read.says(0, "GIF89a")) return null
    return PictureHeader(PictureFormat.Gif, read.u16le(6), read.u16le(8))
}

private fun webp(read: Reader): PictureHeader? {
    if (!read.says(0, "RIFF") || !read.says(8, "WEBP")) return null
    return when {
        // Lossy: a three-byte frame tag, a start code, then two fourteen-bit sides.
        read.says(12, "VP8 ") -> {
            if (read.u8(23) != 0x9D || read.u8(24) != 0x01 || read.u8(25) != 0x2A) return null
            val width = read.u16le(26)
            val height = read.u16le(28)
            if (width < 0 || height < 0) return null
            PictureHeader(PictureFormat.Webp, width and 0x3FFF, height and 0x3FFF)
        }
        // Lossless: one signature byte, then both sides packed into twenty-eight bits, each less one.
        read.says(12, "VP8L") -> {
            if (read.u8(20) != 0x2F) return null
            val bits = packed(read, 21) ?: return null
            PictureHeader(PictureFormat.Webp, (bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
        }
        // Extended: the canvas, each side less one, in three bytes.
        read.says(12, "VP8X") -> {
            val width = read.u24le(24)
            val height = read.u24le(27)
            if (width < 0 || height < 0) return null
            PictureHeader(PictureFormat.Webp, width + 1, height + 1)
        }
        else -> null
    }
}

/** Four little-endian bytes as they are, top bit and all — the lossless header uses every one. */
private fun packed(read: Reader, at: Int): Int? {
    val b = IntArray(4) { read.u8(at + it) }
    if (b.any { it < 0 }) return null
    return b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
}

private fun jpeg(read: Reader): PictureHeader? {
    if (read.u8(0) != 0xFF || read.u8(1) != 0xD8) return null

    var at = 2
    var turned = false
    while (at < read.size) {
        if (read.u8(at) != 0xFF) return null
        // Any number of fill bytes may sit in front of a marker.
        var marker = read.u8(at + 1)
        while (marker == 0xFF) {
            at++
            marker = read.u8(at + 1)
        }
        if (marker < 0) return null
        at += 2

        when {
            // Markers that are only themselves: restarts, and a private one from 1992.
            marker in 0xD0..0xD7 || marker == 0x01 -> continue
            // The image data starts, or the file ends, and no frame was declared before either.
            marker == 0xDA || marker == 0xD9 -> return null
        }

        val length = read.u16be(at)
        if (length < 2) return null

        when {
            // A start of frame. C4, C8 and CC share the range and are tables, not frames.
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                val height = read.u16be(at + 3)
                val width = read.u16be(at + 5)
                if (width < 0 || height < 0) return null
                return if (turned) {
                    PictureHeader(PictureFormat.Jpeg, height, width)
                } else {
                    PictureHeader(PictureFormat.Jpeg, width, height)
                }
            }
            marker == 0xE1 && read.says(at + 2, "Exif") -> turned = turnsOnItsSide(read, at + 8, at + length)
        }
        at += length
    }
    return null
}

/**
 * Whether the orientation recorded at [tiff] is one of the four that swap width for height.
 *
 * Every phone camera writes this. The sensor is fixed in the phone, so a portrait photograph is a
 * landscape one with a note attached, and a size read without the note is the wrong way round for
 * most pictures anyone takes.
 */
private fun turnsOnItsSide(read: Reader, tiff: Int, end: Int): Boolean {
    val little = when {
        read.says(tiff, "II") -> true
        read.says(tiff, "MM") -> false
        else -> return false
    }
    fun u16(at: Int) = if (little) read.u16le(at) else read.u16be(at)
    fun u32(at: Int) = if (little) read.u32le(at) else read.u32be(at)

    if (u16(tiff + 2) != 42) return false
    val directory = u32(tiff + 4).takeIf { it >= 8 }?.let { tiff + it } ?: return false
    val entries = u16(directory).takeIf { it in 0..512 } ?: return false

    for (index in 0 until entries) {
        val entry = directory + 2 + index * 12
        if (entry + 12 > end) return false
        if (u16(entry) != ORIENTATION) continue
        // A SHORT, so it sits in the first two bytes of the value field.
        return u16(entry + 8) in 5..8
    }
    return false
}

private const val ORIENTATION = 0x0112
