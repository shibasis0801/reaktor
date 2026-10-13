package dev.shibasis.reaktor.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import kotlin.js.Promise

/**
 * Reads PDF text with pdf.js. Browser, and Node for the tests.
 *
 * On the page's own thread. pdf.js would rather have a worker, and a worker is a second file the
 * page has to be able to fetch from somewhere — which is a deployment question this module cannot
 * answer for whoever links it. Handing it the worker's code directly makes it run in place: slower
 * for a long document, and nothing to configure.
 *
 * Text is taken from the page's content stream and nothing is drawn. That matters: the one serious
 * hole pdf.js has had (CVE-2024-4367) ran script from a crafted font while *rendering* glyphs.
 * This version is past the fix, this never renders, and `isEvalSupported` is off regardless — a
 * PDF is a file from somewhere else being parsed in the origin that holds the app's keys.
 */
class PdfJsReader : PdfReader {
    override suspend fun text(bytes: ByteArray): PdfText? {
        if (!isPdf(bytes)) return null
        return try {
            read(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Throwable) {
            // pdf.js rejects with its own error types — PasswordException, InvalidPDFException —
            // which arrive here as whatever a rejected promise carries.
            null
        }
    }

    private suspend fun read(bytes: ByteArray): PdfText {
        // Seen by pdf.js when it looks for a worker, and used in place of starting one.
        val inPlace: dynamic = js("({})")
        inPlace.WorkerMessageHandler = WorkerMessageHandler
        js("globalThis").pdfjsWorker = inPlace

        // Its own copy: pdf.js takes ownership of the buffer it is given and detaches it.
        val data = Uint8Array(bytes.size)
        for (index in bytes.indices) data[index] = bytes[index]

        val options: dynamic = js("({})")
        options.data = data
        options.isEvalSupported = false
        options.useSystemFonts = false
        options.disableFontFace = true
        options.verbosity = 0

        val task: dynamic = getDocument(options)
        val document: dynamic = task.promise.unsafeCast<Promise<dynamic>>().await()
        try {
            val count = document.numPages.unsafeCast<Int>()
            val pages = (1..count).map { number ->
                val page: dynamic = document.getPage(number).unsafeCast<Promise<dynamic>>().await()
                val content: dynamic = page.getTextContent().unsafeCast<Promise<dynamic>>().await()
                val items = content.items.unsafeCast<Array<dynamic>>()
                val text = buildString {
                    items.forEach { item ->
                        // Marked-content items have no string; only text runs do.
                        val run = item.str as? String ?: return@forEach
                        append(run)
                        if (item.hasEOL == true) append('\n')
                    }
                }
                page.cleanup()
                PdfPage(number, text.trim())
            }
            return PdfText(pages)
        } finally {
            task.destroy()
        }
    }
}
