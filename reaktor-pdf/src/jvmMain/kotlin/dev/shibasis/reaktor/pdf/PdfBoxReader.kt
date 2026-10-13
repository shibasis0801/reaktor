package dev.shibasis.reaktor.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/** Reads PDF text with Apache PDFBox. Desktop and server. */
class PdfBoxReader : PdfReader {
    override suspend fun text(bytes: ByteArray): PdfText? {
        if (!isPdf(bytes)) return null
        return withContext(Dispatchers.IO) {
            try {
                Loader.loadPDF(bytes).use { document ->
                    val stripper = PDFTextStripper().apply {
                        // Reading order rather than the order the file happens to draw things in,
                        // which for a two-column page is frequently not the same.
                        sortByPosition = true
                    }
                    PdfText(
                        (1..document.numberOfPages).map { number ->
                            stripper.startPage = number
                            stripper.endPage = number
                            PdfPage(number, stripper.getText(document).trim())
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreadable: Exception) {
                // A password, a truncated file, a font table that contradicts itself. PDFBox
                // reports all of them by throwing, and to a caller they are one answer.
                null
            }
        }
    }
}
