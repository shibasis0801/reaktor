package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.adapters.WebFileAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebFileAdapterTest {
    @Test
    fun writesReadsCopiesAndDeletesFilesInBrowser() = runTest {
        val files = WebFileAdapter(
            cacheDirectory = "js-test-cache",
            documentDirectory = "js-test-documents",
        )
        val source = files.resolvePath("source.txt")
        val copy = files.resolvePath("copy.txt")
        val expected = "browser-opfs-smoke-test"

        files.delete(source)
        files.delete(copy)

        assertFalse(files.exists(source))
        assertNull(files.readTextFile(source))

        files.writeTextFile(source, expected)

        assertTrue(files.exists(source))
        assertEquals(expected, files.readTextFile(source))

        files.copy(source, copy)

        assertTrue(files.exists(copy))
        assertEquals(expected, files.readTextFile(copy))

        files.delete(source)
        files.delete(copy)

        assertFalse(files.exists(source))
        assertFalse(files.exists(copy))
        assertNull(files.readTextFile(source))
    }
}
