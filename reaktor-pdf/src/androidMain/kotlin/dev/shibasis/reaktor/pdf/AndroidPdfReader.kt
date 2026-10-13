package dev.shibasis.reaktor.pdf

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads PDF text with the platform's own reader.
 *
 * Android could draw a PDF page from version 5 and could not tell you what it said until version
 * 15. Below that this returns null, the same as for a file it cannot open: [available] says which,
 * for an app that wants to tell its user why.
 *
 * The file is parsed in this process, by the system's PDF library. That is the same code the
 * system's own viewer runs, and it is native code reading a file from somewhere else — worth
 * knowing if what the app holds is worth more than what it is being asked to read.
 */
class AndroidPdfReader : PdfReader {
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override suspend fun text(bytes: ByteArray): PdfText? {
        if (!available || !isPdf(bytes)) return null
        return withContext(Dispatchers.IO) {
            try {
                read(bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreadable: Exception) {
                // IOException for a file that is not one, SecurityException for one that wants a
                // password. Either way there is no text to be had from it here.
                null
            }
        }
    }

    private fun read(bytes: ByteArray): PdfText? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null

        // The renderer wants a file it can seek in. An anonymous one in memory is that, and leaves
        // nothing on disk: these bytes may be the only unencrypted copy the app ever makes.
        val memory = Os.memfd_create("reaktor-pdf", 0)
        val seekable = try {
            var written = 0
            while (written < bytes.size) {
                written += Os.write(memory, bytes, written, bytes.size - written)
            }
            Os.lseek(memory, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(memory)
        } finally {
            Os.close(memory)
        }

        // The renderer owns the descriptor from here and closes it.
        return PdfRenderer(seekable).use { renderer ->
            PdfText(
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        PdfPage(index + 1, page.textContents.joinToString("\n") { it.text }.trim())
                    }
                },
            )
        }
    }
}
