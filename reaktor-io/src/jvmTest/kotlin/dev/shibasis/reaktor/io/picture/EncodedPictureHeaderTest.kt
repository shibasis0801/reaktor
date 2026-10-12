package dev.shibasis.reaktor.io.picture

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same reader, against files a real encoder wrote rather than ones written out by hand. */
class EncodedPictureHeaderTest {
    private fun encoded(format: String, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun whatAnEncoderWritesIsReadBack() {
        val sizes = listOf(1 to 1, 17 to 3, 640 to 480, 1080 to 2400, 4000 to 3000)
        val formats = mapOf("png" to PictureFormat.Png, "jpg" to PictureFormat.Jpeg, "gif" to PictureFormat.Gif)
        for ((name, format) in formats) {
            for ((width, height) in sizes) {
                assertEquals(
                    PictureHeader(format, width, height),
                    pictureHeader(encoded(name, width, height)),
                    "$name $width×$height",
                )
            }
        }
    }
}
