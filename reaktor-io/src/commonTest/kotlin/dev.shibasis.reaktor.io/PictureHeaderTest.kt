package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.picture.PictureFormat
import dev.shibasis.reaktor.io.picture.PictureHeader
import dev.shibasis.reaktor.io.picture.pictureHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headers written out by hand from each format's specification, so that what is being checked is
 * the reading and not an encoder. The JVM suite checks the same reader against real encoders.
 */
class PictureHeaderTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun text(value: String) = value.encodeToByteArray()
    private fun be16(value: Int) = bytes(value shr 8, value)
    private fun be32(value: Int) = bytes(value ushr 24, value shr 16, value shr 8, value)
    private fun le16(value: Int) = bytes(value, value shr 8)
    private fun le24(value: Int) = bytes(value, value shr 8, value shr 16)
    private fun le32(value: Int) = bytes(value, value shr 8, value shr 16, value ushr 24)

    private fun png(width: Int, height: Int) =
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + text("IHDR") +
            be32(width) + be32(height) + bytes(8, 6, 0, 0, 0)

    private fun segment(marker: Int, payload: ByteArray) = bytes(0xFF, marker) + be16(payload.size + 2) + payload

    private fun frame(marker: Int, width: Int, height: Int) =
        segment(marker, bytes(8) + be16(height) + be16(width) + bytes(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))

    private val jfif = segment(0xE0, text("JFIF") + bytes(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))

    /** An Exif block holding one tag: which way up the picture goes. */
    private fun exif(orientation: Int, littleEndian: Boolean = false): ByteArray {
        val tiff = if (littleEndian) {
            text("II") + le16(42) + le32(8) + le16(1) +
                le16(0x0112) + le16(3) + le32(1) + le16(orientation) + le16(0) + le32(0)
        } else {
            text("MM") + be16(42) + be32(8) + be16(1) +
                be16(0x0112) + be16(3) + be32(1) + be16(orientation) + be16(0) + be32(0)
        }
        return segment(0xE1, text("Exif") + bytes(0, 0) + tiff)
    }

    private val soi = bytes(0xFF, 0xD8)

    private fun riff(chunk: ByteArray) = text("RIFF") + le32(chunk.size + 4) + text("WEBP") + chunk

    @Test
    fun aPngSaysItsSizeInItsFirstChunk() {
        assertEquals(PictureHeader(PictureFormat.Png, 1080, 2400), pictureHeader(png(1080, 2400)))
    }

    @Test
    fun aJpegIsReadPastWhateverComesBeforeItsFrame() {
        val picture = soi + jfif + segment(0xDB, ByteArray(65)) + frame(0xC0, 4000, 3000)
        assertEquals(PictureHeader(PictureFormat.Jpeg, 4000, 3000), pictureHeader(picture))
    }

    @Test
    fun aProgressiveJpegIsStillAJpeg() {
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 640, 480),
            pictureHeader(soi + jfif + frame(0xC2, 640, 480)),
        )
    }

    /** C4 sits in the middle of the frame markers and is a table. Its bytes are not a size. */
    @Test
    fun aHuffmanTableIsNotMistakenForAFrame() {
        val table = segment(0xC4, bytes(0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 320, 200),
            pictureHeader(soi + table + frame(0xC0, 320, 200)),
        )
    }

    @Test
    fun aPhotographStoredOnItsSideIsMeasuredTheWayItWillBeSeen() {
        // What a phone held upright writes: a landscape frame, and a note to turn it.
        for (orientation in 5..8) {
            assertEquals(
                PictureHeader(PictureFormat.Jpeg, 3000, 4000),
                pictureHeader(soi + exif(orientation) + frame(0xC0, 4000, 3000)),
                "orientation $orientation",
            )
        }
        for (orientation in 1..4) {
            assertEquals(
                PictureHeader(PictureFormat.Jpeg, 4000, 3000),
                pictureHeader(soi + exif(orientation) + frame(0xC0, 4000, 3000)),
                "orientation $orientation",
            )
        }
    }

    @Test
    fun theNoteIsReadInEitherByteOrder() {
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 3000, 4000),
            pictureHeader(soi + exif(6, littleEndian = true) + frame(0xC0, 4000, 3000)),
        )
    }

    @Test
    fun fillBytesBeforeAMarkerAreSkipped() {
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 10, 20),
            pictureHeader(soi + bytes(0xFF, 0xFF, 0xFF) + frame(0xC0, 10, 20).drop(1).toByteArray()),
        )
    }

    @Test
    fun aGifSaysItsSizeLittleEndian() {
        assertEquals(
            PictureHeader(PictureFormat.Gif, 500, 281),
            pictureHeader(text("GIF89a") + le16(500) + le16(281) + bytes(0xF7, 0, 0)),
        )
    }

    @Test
    fun aLossyWebp() {
        val chunk = text("VP8 ") + le32(30) + bytes(0x30, 0x01, 0x00, 0x9D, 0x01, 0x2A) +
            le16(1280) + le16(720) + ByteArray(20)
        assertEquals(PictureHeader(PictureFormat.Webp, 1280, 720), pictureHeader(riff(chunk)))
    }

    @Test
    fun aLosslessWebp() {
        // Fourteen bits of width less one, then fourteen of height less one.
        val packed = (1920 - 1) or ((1080 - 1) shl 14)
        val chunk = text("VP8L") + le32(10) + bytes(0x2F) + le32(packed) + ByteArray(5)
        assertEquals(PictureHeader(PictureFormat.Webp, 1920, 1080), pictureHeader(riff(chunk)))
    }

    /** Both sides at their largest set the top bit, which a signed read would call negative. */
    @Test
    fun aLosslessWebpAtItsLargest() {
        val packed = 0x3FFF or (0x3FFF shl 14) or (0x7 shl 28)
        val chunk = text("VP8L") + le32(10) + bytes(0x2F) + le32(packed) + ByteArray(5)
        assertEquals(PictureHeader(PictureFormat.Webp, 16384, 16384), pictureHeader(riff(chunk)))
    }

    @Test
    fun anExtendedWebp() {
        val chunk = text("VP8X") + le32(10) + bytes(0x10, 0, 0, 0) + le24(4032 - 1) + le24(3024 - 1)
        assertEquals(PictureHeader(PictureFormat.Webp, 4032, 3024), pictureHeader(riff(chunk)))
    }

    @Test
    fun thingsThatAreNotPicturesAreNotPictures() {
        assertNull(pictureHeader(ByteArray(0)))
        assertNull(pictureHeader(text("# A plan\n\nNot a picture.")))
        assertNull(pictureHeader(text("%PDF-1.7\n")))
        // A document that draws, and one a caller has to choose to render.
        assertNull(pictureHeader(text("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>")))
        assertNull(pictureHeader(text("RIFF") + le32(4) + text("WAVE")))
    }

    @Test
    fun aPictureThatClaimsNoSizeIsNotOne() {
        assertNull(pictureHeader(png(0, 100)))
        assertNull(pictureHeader(png(100, 0)))
        assertNull(pictureHeader(soi + frame(0xC0, 0, 0)))
        assertNull(pictureHeader(text("GIF89a") + le16(0) + le16(0)))
    }

    /** A side with its top bit set is two thousand million pixels or a negative number. Neither is drawn. */
    @Test
    fun aSizeThatCannotBeASizeIsRefused() {
        val absurd = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + text("IHDR") +
            bytes(0xFF, 0xFF, 0xFF, 0xFF) + be32(1) + bytes(8, 6, 0, 0, 0)
        assertNull(pictureHeader(absurd))
    }

    @Test
    fun theLargestHonestPngIsReportedSoTheCallerCanRefuseIt() {
        // Small on disk, enormous as a bitmap. The reader's job is to say so, not to judge.
        val header = pictureHeader(png(20_000, 20_000))
        assertEquals(400_000_000L, header?.pixels)
    }

    /**
     * Every prefix of every kind of file. Cut short anywhere, the answer is the right size or "not
     * one" — never a throw, and never a size made of whatever bytes happened to be missing.
     */
    @Test
    fun aFileCutShortAnywhereIsReadRightOrNotAtAll() {
        val whole = listOf(
            png(8, 8),
            soi + exif(6) + jfif + frame(0xC0, 8, 8),
            text("GIF89a") + le16(8) + le16(8),
            riff(text("VP8 ") + le32(30) + bytes(0x30, 1, 0, 0x9D, 0x01, 0x2A) + le16(8) + le16(8)),
            riff(text("VP8L") + le32(10) + bytes(0x2F) + le32(7 or (7 shl 14))),
            riff(text("VP8X") + le32(10) + bytes(0, 0, 0, 0) + le24(7) + le24(7)),
        )
        for (file in whole) {
            val entire = pictureHeader(file)
            assertEquals(8, entire?.width)
            assertEquals(8, entire?.height)
            var read = 0
            for (cut in 0 until file.size) {
                val part = pictureHeader(file.copyOf(cut)) ?: continue
                assertEquals(entire, part, "cut at $cut of ${file.size}")
                read++
            }
            // The size is near the front, so most cuts lose it. One that never did is a reader
            // that is not looking at the bytes.
            assertTrue(read < file.size - 8, "read a size from $read of ${file.size} prefixes")
        }
    }

    /** A segment that says it is longer than the file must not send the reader off the end. */
    @Test
    fun aJpegSegmentLongerThanTheFile() {
        assertNull(pictureHeader(soi + bytes(0xFF, 0xE0, 0xFF, 0xFF, 1, 2, 3)))
        assertNull(pictureHeader(soi + bytes(0xFF, 0xE0, 0x00, 0x00)))
        assertNull(pictureHeader(soi + bytes(0xFF, 0xE0, 0x00, 0x01)))
    }

    /** An Exif directory that points outside itself is ignored; the frame is still read. */
    @Test
    fun aBrokenOrientationNoteDoesNotCostThePicture() {
        val wild = segment(0xE1, text("Exif") + bytes(0, 0) + text("MM") + be16(42) + be32(0x7FFFFFF0))
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 4000, 3000),
            pictureHeader(soi + wild + frame(0xC0, 4000, 3000)),
        )
        val manyEntries = segment(0xE1, text("Exif") + bytes(0, 0) + text("MM") + be16(42) + be32(8) + be16(0xFFFF))
        assertEquals(
            PictureHeader(PictureFormat.Jpeg, 4000, 3000),
            pictureHeader(soi + manyEntries + frame(0xC0, 4000, 3000)),
        )
    }
}
