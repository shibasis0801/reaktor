package dev.shibasis.dependeasy

import com.sun.net.httpserver.HttpServer
import dev.shibasis.dependeasy.toolchain.InstallTool
import dev.shibasis.dependeasy.handles.paths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class InstallToolTest {
    @TempDir lateinit var directory: File

    private fun archive(): ByteArray = ByteArrayOutputStream().apply {
        TarArchiveOutputStream(GZIPOutputStream(this)).use { tar ->
            val bytes = "#!/bin/sh\nexit 0\n".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("package/tool").apply { size = bytes.size.toLong() })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }.toByteArray()

    @Test fun `transient downloads retry and verified tools are reused`() {
        val bytes = archive()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tool") { exchange ->
            if (requests.incrementAndGet() == 1) exchange.sendResponseHeaders(503, -1)
            else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val task = task(bytes)
            task.url.set("http://127.0.0.1:${server.address.port}/tool")
            task.install()
            assertTrue(directory.resolve("tools/tool").canExecute())
            assertEquals(2, requests.get())
            task.install()
            assertEquals(2, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun `a checksum failure preserves the previous executable`() {
        val bytes = archive()
        val source = directory.resolve("archive.tgz").apply { writeBytes(bytes) }
        val previous = directory.resolve("tools/tool").apply { parentFile.mkdirs(); writeText("previous") }
        val task = task(bytes)
        task.url.set(source.toURI().toString())
        task.checksum.set("0".repeat(64))
        assertFailsWith<IllegalStateException> { task.install() }
        assertEquals("previous", previous.readText())
        assertFalse(directory.resolve("tools/.sha256").exists())
        assertEquals(setOf("tool", ".lock"), previous.parentFile.list()!!.toSet())
    }

    @Test fun `raw jars are checksum qualified and a corrupt download is rejected`() {
        val bytes = "standalone jar fixture".toByteArray()
        val source = directory.resolve("fixture.jar").apply { writeBytes(bytes) }
        val task = task(bytes).apply {
            format.set(dev.shibasis.dependeasy.toolchain.ToolArchive.RawFile)
            url.set(source.toURI().toString()); binaryName.set("fixture.jar")
        }
        task.install()
        assertContentEquals(bytes, directory.resolve("tools/fixture.jar").readBytes())
        val roots = listOf("workspace/build", "slots/job-1/workspace/build").map { path ->
            ProjectBuilder.builder().withProjectDir(directory.resolve(path).apply { mkdirs() }).build()
        }
        val outputs = roots.map { project -> project.files(directory.resolve("tools/fixture.jar")).paths(project) }
        assertEquals(listOf(directory.resolve("tools/fixture.jar").canonicalPath), outputs.first())
        assertEquals(outputs.first(), outputs.last())
        task.install()
        directory.resolve("tools/.sha256").delete()
        source.writeText("tampered")
        assertFailsWith<IllegalStateException> { task.install() }
        assertContentEquals(bytes, directory.resolve("tools/fixture.jar").readBytes())
    }

    private fun task(bytes: ByteArray): InstallTool = ProjectBuilder.builder().build()
        .tasks.create("qualifiedTool", InstallTool::class.java).apply {
            checksum.set(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            archiveEntry.set("package/tool")
            binaryName.set("tool")
            destination.set(directory.resolve("tools"))
        }
}
