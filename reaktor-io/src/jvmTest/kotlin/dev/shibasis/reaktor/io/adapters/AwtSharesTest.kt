package dev.shibasis.reaktor.io.adapters

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a drag or a paste turns into.
 *
 * The transfer, not the toolkit: a `Transferable` is what both mechanisms hand over, and it can be
 * built without a window, a display or a clipboard. Which means the part that decides *what the
 * user meant* is testable, and only the plumbing that delivers it is not.
 */
class AwtSharesTest {

    /** A clipboard's worth of offers, in the order a real one presents them. */
    private class Offer(private val contents: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = contents.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = contents.containsKey(flavor)
        override fun getTransferData(flavor: DataFlavor): Any =
            contents[flavor] ?: throw IllegalArgumentException("$flavor was not offered")
    }

    private fun tempFile(name: String, body: String): File =
        File.createTempFile("awt-shares-", "-$name").apply {
            writeText(body)
            deleteOnExit()
        }

    @Test
    fun `text alone becomes a text share`() {
        val share = Offer(mapOf(DataFlavor.stringFlavor to "https://example.com/spec")).toShare()

        assertNotNull(share)
        assertEquals("text/plain", share.mime)
        assertEquals("https://example.com/spec", share.text)
        assertTrue(share.fileUris.isEmpty())
    }

    /**
     * The case that decides whether copying a file from a file manager works.
     *
     * Finder and most editors put the path on the clipboard *as well as* the file, so a rule that
     * preferred text would drop a string that reads like a path instead of the document.
     */
    @Test
    fun `a file offered alongside its path is taken as the file`() {
        val file = tempFile("notes.txt", "chapter one")

        val share = Offer(
            mapOf(
                DataFlavor.javaFileListFlavor to listOf(file),
                DataFlavor.stringFlavor to file.absolutePath,
            ),
        ).toShare()

        assertNotNull(share)
        assertEquals(listOf(file.absolutePath), share.fileUris)
        assertNull(share.text)
        assertEquals(file.name, share.title)
    }

    @Test
    fun `several files have no single type to claim`() {
        val share = Offer(
            mapOf(
                DataFlavor.javaFileListFlavor to listOf(
                    tempFile("a.txt", "a"),
                    tempFile("b.txt", "b"),
                ),
            ),
        ).toShare()

        assertNotNull(share)
        assertEquals("*/*", share.mime)
        assertEquals(2, share.fileUris.size)
    }

    /** A dragged file that has since been deleted must not become an empty share. */
    @Test
    fun `a file list of things that are not there falls through to the text`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "awt-shares-not-here")

        val share = Offer(
            mapOf(
                DataFlavor.javaFileListFlavor to listOf(missing),
                DataFlavor.stringFlavor to "the path, at least",
            ),
        ).toShare()

        assertNotNull(share)
        assertEquals("the path, at least", share.text)
        assertTrue(share.fileUris.isEmpty())
    }

    /**
     * A screenshot has no path, so the one thing this staging is for is that it gets one.
     */
    @Test
    fun `a pasted image is staged where it can be read back`() {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)

        val share = Offer(mapOf(DataFlavor.imageFlavor to image)).toShare()

        assertNotNull(share)
        assertEquals("image/png", share.mime)

        val staged = File(share.fileUris.single())
        assertTrue(staged.isFile, "the image should have been written to disk")
        assertNotNull(ImageIO.read(staged), "and it should still be a readable PNG")
    }

    @Test
    fun `an empty transfer is not a share`() {
        assertNull(Offer(emptyMap()).toShare())
        assertNull(Offer(mapOf(DataFlavor.stringFlavor to "   ")).toShare())
    }
}
