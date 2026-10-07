package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Handing a file to the person at a desktop: it is in their folder, under a name, and nothing else
 * was touched.
 */
class DesktopShareAdapterTest {
    private val home: File = Files.createTempDirectory("reaktor-share").toFile()
    private val downloads = File(home, "Downloads")
    private val saved = mutableListOf<File>()
    private val shown = mutableListOf<File>()
    private val adapter = DesktopShareAdapter(downloads, reveal = { shown += it }, onSaved = { saved += it })

    @AfterTest
    fun cleanUp() {
        home.deleteRecursively()
    }

    private fun share(name: String, body: String = "bytes of $name") = runBlocking {
        adapter.shareFile(SharePayload(name, body.encodeToByteArray(), "application/octet-stream"))
    }

    @Test
    fun aFileIsSavedShownAndReported() {
        assertTrue(share("plan.pdf", "%PDF"))

        val file = File(downloads, "plan.pdf")
        assertContentEquals("%PDF".encodeToByteArray(), file.readBytes())
        assertEquals(listOf(file), saved)
        assertEquals(listOf(file), shown)
    }

    @Test
    fun aSecondFileOfTheSameNameDoesNotReplaceTheFirst() {
        share("plan.pdf", "one")
        share("plan.pdf", "two")
        share("plan.pdf", "three")
        share("README", "a")
        share("README", "b")

        assertEquals("one", File(downloads, "plan.pdf").readText())
        assertEquals("two", File(downloads, "plan (2).pdf").readText())
        assertEquals("three", File(downloads, "plan (3).pdf").readText())
        assertEquals("b", File(downloads, "README (2)").readText())
    }

    /**
     * The name is the sender's. Whatever it says, the file lands in the folder and nowhere else.
     */
    @Test
    fun aNameThatPointsSomewhereElseStillLandsInTheFolder() {
        val precious = File(home, "precious.txt").apply { writeText("do not touch") }

        assertTrue(share("../precious.txt", "overwritten"))
        assertTrue(share("../../../../../../tmp/elsewhere.txt", "escaped"))
        assertTrue(share("/etc/hosts", "replaced"))

        assertEquals("do not touch", precious.readText())
        assertEquals(
            listOf("elsewhere.txt", "hosts", "precious.txt"),
            downloads.list()!!.sorted(),
        )
        assertTrue(saved.all { it.parentFile == downloads })
    }

    @Test
    fun aFolderThatCannotBeWrittenIsAFalseAndNotAThrow() {
        // A file where the folder should be: nothing can be created inside it.
        val blocked = File(home, "not-a-folder").apply { writeText("") }
        val stuck = DesktopShareAdapter(blocked, reveal = {}, onSaved = { saved += it })

        val shared = runBlocking { stuck.shareFile(SharePayload("a.txt", "a")) }

        assertEquals(false, shared)
        assertTrue(saved.isEmpty())
    }
}
