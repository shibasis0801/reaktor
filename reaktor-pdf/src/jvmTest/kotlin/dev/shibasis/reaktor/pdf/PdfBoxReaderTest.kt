package dev.shibasis.reaktor.pdf

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfBoxReaderTest {
    private val reader = PdfBoxReader()

    @Test fun readsEachPageInOrder() = runTest { PdfReaderChecks.readsEachPageInOrder(reader) }
    @Test fun keepsLinesApart() = runTest { PdfReaderChecks.keepsLinesApart(reader) }
    @Test fun whatIsNotAPdfIsNotRead() = runTest { PdfReaderChecks.whatIsNotAPdfIsNotRead(reader) }
    @Test fun aFileThatOnlyStartsLikeOneIsNotRead() = runTest { PdfReaderChecks.aFileThatOnlyStartsLikeOneIsNotRead(reader) }
    @Test fun aFileCutShortDoesNotThrow() = runTest { PdfReaderChecks.aFileCutShortDoesNotThrow(reader) }
    @Test fun aPdfWithNoWordsIsBlankAndNotMissing() = runTest { PdfReaderChecks.aPdfWithNoWordsIsBlankAndNotMissing(reader) }

    /** A PDF as a real writer makes one: compressed streams, a compressed cross-reference table. */
    private fun written(password: String? = null, vararg lines: String): ByteArray {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.setLeading(16f)
                stream.newLineAtOffset(72f, 720f)
                lines.forEach { stream.showText(it); stream.newLine() }
                stream.endText()
            }
            if (password != null) {
                document.protect(StandardProtectionPolicy("owner-$password", password, AccessPermission()))
            }
            return ByteArrayOutputStream().also(document::save).toByteArray()
        }
    }

    @Test
    fun aCompressedPdfIsRead() = runTest {
        val bytes = written(null, "Decide the cutover week.", "Agree the freeze dates.")
        // Compressed, so the words are not sitting in the file to be found by accident.
        assertTrue("cutover" !in bytes.decodeToString())

        val text = assertNotNull(reader.text(bytes))
        assertTrue("Decide the cutover week." in text.pages.single().text)
        assertTrue("Agree the freeze dates." in text.pages.single().text)
    }

    /** It could be opened with the password. Nobody here has it, so the answer is no text. */
    @Test
    fun aPdfThatWantsAPasswordIsNotRead() = runTest {
        assertNull(reader.text(written("s3cret", "Nobody without the password reads this.")))
    }
}
