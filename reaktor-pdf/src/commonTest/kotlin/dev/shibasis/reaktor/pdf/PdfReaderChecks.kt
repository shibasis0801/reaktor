package dev.shibasis.reaktor.pdf

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every reader has to do, whichever library is underneath.
 *
 * Plain functions that each platform's suite calls with its own reader. The readers disagree about
 * whitespace, so nothing here compares whole strings: it asks whether the words are there, on the
 * right page, in the right order.
 */
object PdfReaderChecks {
    private val plan = SamplePdf.of(
        listOf("Sequencing against RSEC", "Decide the cutover week.", "Confirm the rollback owner (platform team)."),
        listOf("Cost", "Storage and egress are the recurring cost."),
    )

    suspend fun readsEachPageInOrder(reader: PdfReader) {
        val text = assertNotNull(reader.text(plan), "a plain two-page PDF was not read")

        assertEquals(listOf(1, 2), text.pages.map { it.number })
        val first = text.pages[0].text
        val second = text.pages[1].text

        assertTrue("Sequencing against RSEC" in first, first)
        assertTrue("Confirm the rollback owner (platform team)." in first, first)
        assertTrue(first.indexOf("Sequencing") < first.indexOf("Decide"), "lines are out of order: $first")
        assertTrue(first.indexOf("Decide") < first.indexOf("Confirm"), "lines are out of order: $first")

        assertTrue("Storage and egress" in second, second)
        // A page's words stay on its page.
        assertTrue("Storage" !in first && "Sequencing" !in second)
    }

    /** Three lines drawn on three lines come back as three lines, not as one run of words. */
    suspend fun keepsLinesApart(reader: PdfReader) {
        val text = assertNotNull(reader.text(plan))
        val lines = text.pages[0].text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(3, lines.size, "page one came back as: $lines")
    }

    suspend fun whatIsNotAPdfIsNotRead(reader: PdfReader) {
        assertNull(reader.text(ByteArray(0)))
        assertNull(reader.text("# A plan\n\nNot a PDF.".encodeToByteArray()))
        assertNull(reader.text(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    /** It says it is one in its first line, and nothing after that agrees. */
    suspend fun aFileThatOnlyStartsLikeOneIsNotRead(reader: PdfReader) {
        assertNull(reader.text("%PDF-1.7\nand then nothing a reader could use".encodeToByteArray()))
    }

    /**
     * Cut off partway. Some readers rebuild what they can and some give up; both are fine. What is
     * not fine is throwing, or returning words that were never in the file.
     */
    suspend fun aFileCutShortDoesNotThrow(reader: PdfReader) {
        for (keep in listOf(plan.size / 4, plan.size / 2, plan.size - 40)) {
            val text = reader.text(plan.copyOf(keep)) ?: continue
            val words = text.pages.joinToString(" ") { it.text }
            assertTrue(words.isBlank() || "Sequencing" in words || "Cost" in words, "cut at $keep read: $words")
        }
    }

    /** A page with nothing on it opens and is blank. That is a different answer from "unreadable". */
    suspend fun aPdfWithNoWordsIsBlankAndNotMissing(reader: PdfReader) {
        val text = assertNotNull(reader.text(SamplePdf.of(emptyList())), "an empty page should still open")
        assertEquals(1, text.pages.size)
        assertTrue(text.isBlank)
    }
}
