package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SafeFilesTest {
    @TempDir lateinit var directory: File

    @Test fun `cleanup rejects the boundary and files outside it`() {
        val boundary = directory.resolve("build").apply { mkdir() }
        val outside = directory.resolve("source.txt").apply { writeText("preserve") }
        assertFailsWith<IllegalArgumentException> { boundary.deleteTreeSafely(within = boundary) }
        assertFailsWith<IllegalArgumentException> { outside.deleteTreeSafely(within = boundary) }
        assertEquals("preserve", outside.readText())
    }

    @Test fun `cleanup removes nested links without following them`() {
        val output = directory.resolve("output").apply { mkdir() }
        val source = directory.resolve("source").apply { mkdir() }
        source.resolve("keep.txt").writeText("preserve")
        Files.createSymbolicLink(output.resolve("linked-source").toPath(), source.toPath())
        assertTrue(output.deleteTreeSafely(within = directory))
        assertFalse(output.exists())
        assertEquals("preserve", source.resolve("keep.txt").readText())
    }

    @Test fun `cleanup refuses a symbolic link in the target ancestors`() {
        val source = directory.resolve("source").apply { mkdir() }
        source.resolve("keep.txt").writeText("preserve")
        val link = directory.resolve("link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        assertFailsWith<IllegalArgumentException> {
            link.resolve("keep.txt").deleteTreeSafely(within = directory)
        }
        assertEquals("preserve", source.resolve("keep.txt").readText())
    }
}
