package dev.shibasis.reaktor.pdf

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/** The words on one page, in reading order as far as the file allows. [number] counts from one. */
data class PdfPage(val number: Int, val text: String)

/** What a PDF says, page by page. */
data class PdfText(val pages: List<PdfPage>) {
    /**
     * True for a PDF that opened and has no words in it — a scan, nearly always. Pictures of pages
     * are a different problem from text in pages, and a caller should be able to tell "this could
     * not be opened" from "this was opened and there is nothing to read".
     */
    val isBlank: Boolean get() = pages.all { it.text.isBlank() }
}

/**
 * Reads the text out of a PDF.
 *
 * Text only. Showing a page is a different capability with a different cost, and an app that wants
 * to search what it holds, or quote from it, needs the words and not the pixels.
 *
 * An interface with a slot rather than an `expect`, because not being able to is a normal state:
 * Android gained a way to read text from a PDF in version 15, and there is no reader on iOS here at
 * all. A caller reads `Feature.Pdf ?: return` and carries on with a document it cannot read, which
 * is where it was before this module existed.
 *
 * | Platform | Reader | Notes |
 * |---|---|---|
 * | JVM | `PdfBoxReader` | Apache PDFBox |
 * | Android | `AndroidPdfReader` | The platform's own, from API 35. Below that, [text] is null |
 * | Browser | `PdfJsReader` | pdf.js, on the page's own thread |
 * | iOS | — | not written |
 *
 * The three do not produce identical text for the same file: they are three programs with three
 * opinions about where a line ends. Do not compare text across devices, and do not hash it.
 */
interface PdfReader {
    /**
     * The text of the PDF in [bytes], or null when it cannot be had: these are not a PDF, the file
     * is damaged past opening, it needs a password, or this platform has no way to read one.
     *
     * Never throws for a bad file. A PDF comes from somewhere else, and one that will not open is
     * an ordinary thing to be handed.
     */
    suspend fun text(bytes: ByteArray): PdfText?
}

/**
 * Whether [bytes] begin the way a PDF does.
 *
 * By content, not by name or declared type — both of those arrive with the file and are whatever
 * its sender said. The marker may sit a little way in: the format allows junk before it, and real
 * files, mostly ones that passed through a mail gateway, have some.
 */
fun isPdf(bytes: ByteArray): Boolean {
    val marker = "%PDF-"
    val last = minOf(bytes.size - marker.length, 1024)
    for (start in 0..last) {
        if (marker.indices.all { bytes[start + it] == marker[it].code.toByte() }) return true
    }
    return false
}

var Feature.Pdf by CreateSlot<PdfReader>()
