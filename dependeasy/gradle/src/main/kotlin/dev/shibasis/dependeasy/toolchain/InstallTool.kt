package dev.shibasis.dependeasy.toolchain

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.io.IOException
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One checksum-qualified executable per host. No global package installation. */
@DisableCachingByDefault(because = "Tools are shared in the Gradle user home and selected for the current host")
abstract class InstallTool : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val checksum: Property<String>
    @get:Input abstract val archiveEntry: Property<String>
    @get:Input abstract val binaryName: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun install() {
        val directory = destination.get().asFile
        directory.mkdirs()
        FileChannel.open(directory.resolve(".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val binary = directory.resolve(binaryName.get())
                val receipt = directory.resolve(".sha256")
                if (binary.canExecute() && receipt.isFile && receipt.readText() == checksum.get()) return
                val archive = Files.createTempFile(directory.toPath(), "download-", ".tgz")
                val executable = Files.createTempFile(directory.toPath(), "executable-", ".tmp")
                try {
                    download(archive)
                    val digest = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(archive).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == checksum.get()) { "Tool checksum mismatch for ${url.get()}: $actual" }
                    var found = false
                    TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (entry.name == archiveEntry.get() && entry.isFile) {
                                Files.copy(tar, executable, StandardCopyOption.REPLACE_EXISTING)
                                found = true; break
                            }
                        }
                    }
                    check(found) { "Tool archive lacks ${archiveEntry.get()}" }
                    executable.toFile().setExecutable(true, false)
                    Files.move(executable, binary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    receipt.writeText(checksum.get())
                } finally { Files.deleteIfExists(archive); Files.deleteIfExists(executable) }
            }
        }
    }

    private fun download(archive: Path) {
        for (attempt in 1..3) {
            try {
                URI(url.get()).toURL().openConnection().apply {
                    connectTimeout = 30_000; readTimeout = 120_000
                }.getInputStream().use { input -> Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING) }
                return
            } catch (failure: IOException) {
                if (attempt == 3 || Thread.currentThread().isInterrupted) throw failure
                logger.warn("Tool download attempt $attempt failed; retrying: ${failure.message}")
                Thread.sleep(attempt * 1_000L)
            }
        }
    }
}
