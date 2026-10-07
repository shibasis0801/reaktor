package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.adapters.safeFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A name that arrived from somewhere else, about to become a path.
 */
class SafeFileNameTest {

    @Test
    fun anOrdinaryNameIsLeftAsItIs() {
        assertEquals("Q3 review (final).pdf", safeFileName("Q3 review (final).pdf"))
        assertEquals("notes.tar.gz", safeFileName("notes.tar.gz"))
        assertEquals("résumé — v2.docx", safeFileName("résumé — v2.docx"))
    }

    /** Each of these, joined to a staging directory, would have been written outside it. */
    @Test
    fun aNameCannotClimbOutOfTheDirectoryItIsPutIn() {
        val hostile = listOf(
            "../../databases/reaktor.db",
            "..\\..\\databases\\reaktor.db",
            "/etc/passwd",
            "C:\\Windows\\system.ini",
            "a/../../b.txt",
            "..",
            "../",
        )
        for (name in hostile) {
            val safe = safeFileName(name)
            assertFalse('/' in safe || '\\' in safe, "$name → $safe still has a separator")
            assertFalse(safe.startsWith("."), "$name → $safe would be hidden, or is a way up")
            assertTrue(safe.isNotEmpty())
        }
        assertEquals("reaktor.db", safeFileName("../../databases/reaktor.db"))
        assertEquals("passwd", safeFileName("/etc/passwd"))
    }

    @Test
    fun aNameIsNeverEmptyAndNeverHidden() {
        assertEquals("file", safeFileName(""))
        assertEquals("file", safeFileName("   "))
        assertEquals("file", safeFileName("..."))
        // Saved and invisible is, to the person who saved it, not saved.
        assertEquals("env", safeFileName(".env"))
    }

    @Test
    fun charactersAFilesystemRefusesAreReplaced() {
        assertEquals("a_b_c", safeFileName("a:b\u0000c"))
        assertEquals("line_break.txt", safeFileName("line\nbreak.txt"))
    }

    @Test
    fun aVeryLongNameIsCutToSomethingAFilesystemAccepts() {
        assertTrue(safeFileName("x".repeat(5_000) + ".pdf").length <= 200)
    }
}
