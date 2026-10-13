package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.desktop.RuntimeJarStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class RuntimeJarStoreTest {
    @TempDir lateinit var directory: File

    private fun jar(file: File, value: String): File = file.apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fixture.txt")); zip.write(value.toByteArray()); zip.closeEntry()
        }
    }

    @Test fun `live launches retain their jars while new builds replace sources and abandoned jars are collected`() {
        val downloaded = directory.resolve("downloaded")
        val dependency = jar(downloaded.resolve("dependency.jar"), "stable")
        val source = jar(directory.resolve("app.jar"), "first")
        val store = RuntimeJarStore(directory.resolve("runtime"), downloaded)
        val first = store.acquire(listOf(source, dependency))
        val retained = first.entries.first()
        assertEquals(dependency, first.entries.last())
        jar(source, "second")
        val second = store.acquire(listOf(source, dependency))
        assertNotEquals(retained, second.entries.first())
        assertTrue(retained.isFile)
        first.close()
        val third = store.acquire(listOf(source, dependency))
        assertEquals(second.entries, third.entries)
        assertFalse(retained.exists())
        second.close(); third.close()
    }
}
