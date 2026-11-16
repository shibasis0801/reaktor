package dev.shibasis.reaktor.io.picture

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [downscalePicture] on the desktop's decoder, and the orientation note both it and Android turn by. */
class DownscaleTest {
    /** Red on the left half, blue on the right, as [format]. */
    private fun halves(width: Int, height: Int, format: String = "jpeg"): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED; fillRect(0, 0, width / 2, height)
            color = Color.BLUE; fillRect(width / 2, 0, width - width / 2, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    /** [jpeg] with an EXIF note saying it is stored at [orientation], as a phone's camera writes. */
    private fun noted(jpeg: ByteArray, orientation: Int): ByteArray {
        val exif = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0, 34,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        return jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun small(bytes: ByteArray, maxSide: Int = 1600, quality: Int = 80) = runBlocking { downscalePicture(bytes, maxSide, quality) }

    private fun pixels(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun BufferedImage.isRed(x: Int, y: Int) = Color(getRGB(x, y)).let { it.red > 180 && it.blue < 80 }
    private fun BufferedImage.isBlue(x: Int, y: Int) = Color(getRGB(x, y)).let { it.blue > 180 && it.red < 80 }

    @Test
    fun aPhotoComesDownToSizeAndStaysAPicture() {
        val photo = halves(3000, 2000)
        val kept = assertNotNull(small(photo, maxSide = 1200))
        assertEquals(PictureHeader(PictureFormat.Jpeg, 1200, 800), pictureHeader(kept))
        assertTrue(kept.size < photo.size)
        val image = pixels(kept)
        assertTrue(image.isRed(300, 400) && image.isBlue(900, 400))
        // A lower quality is a smaller file of the same picture.
        assertTrue(assertNotNull(small(photo, maxSide = 1200, quality = 30)).size < kept.size)
    }

    @Test
    fun aSmallPictureIsNotMadeLarger() {
        assertEquals(PictureHeader(PictureFormat.Jpeg, 300, 200), pictureHeader(assertNotNull(small(halves(300, 200)))))
        // A PNG comes back a JPEG: one format to keep, whatever was chosen.
        assertEquals(PictureHeader(PictureFormat.Jpeg, 64, 48), pictureHeader(assertNotNull(small(halves(64, 48, "png")))))
    }

    @Test
    fun aPhotoStoredOnItsSideIsTurnedUpright() {
        val stored = halves(400, 200)
        assertEquals(1, pictureOrientation(stored))

        // 6: a quarter turn clockwise to show it. What was stored on the left is at the top.
        val quarter = noted(stored, 6)
        assertEquals(6, pictureOrientation(quarter))
        assertEquals(PictureHeader(PictureFormat.Jpeg, 200, 400), pictureHeader(quarter))
        val upright = assertNotNull(small(quarter))
        assertEquals(PictureHeader(PictureFormat.Jpeg, 200, 400), pictureHeader(upright))
        // The note is gone: the pixels themselves are the right way up now.
        assertEquals(1, pictureOrientation(upright))
        // And with it everything else EXIF carries, a photo's location among it: only pixels are kept.
        assertTrue("Exif" !in String(upright, Charsets.ISO_8859_1))
        pixels(upright).let { assertTrue(it.isRed(100, 60) && it.isBlue(100, 340)) }

        // 8: a quarter turn the other way. The left is at the bottom.
        pixels(assertNotNull(small(noted(stored, 8)))).let { assertTrue(it.isBlue(100, 60) && it.isRed(100, 340)) }
        // 3: upside down. The left is on the right.
        pixels(assertNotNull(small(noted(stored, 3)))).let { assertTrue(it.isBlue(100, 100) && it.isRed(300, 100)) }
        // 2: mirrored.
        pixels(assertNotNull(small(noted(stored, 2)))).let { assertTrue(it.isBlue(100, 100) && it.isRed(300, 100)) }
    }

    @Test
    fun whatWasSeeThroughIsWhite() {
        val clear = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(clear, "png", it) }.toByteArray()
        val flat = pixels(assertNotNull(small(png)))
        assertTrue(Color(flat.getRGB(20, 20)).let { it.red > 240 && it.green > 240 && it.blue > 240 })
    }

    @Test
    fun whatIsNotAPictureIsNull() {
        assertNull(small(ByteArray(0)))
        assertNull(small("Date,Amount\n1,2".encodeToByteArray()))
        // A header that asks for more than can be decoded safely is refused before any decoding.
        val huge = halves(8, 8, "png").also { png ->
            // IHDR's width and height sit at bytes 16 to 23: 20,000 by 20,000.
            listOf(16 to 20_000, 20 to 20_000).forEach { (at, v) ->
                png[at] = (v ushr 24).toByte(); png[at + 1] = (v ushr 16).toByte(); png[at + 2] = (v ushr 8).toByte(); png[at + 3] = v.toByte()
            }
        }
        assertNull(small(huge))
    }
}
