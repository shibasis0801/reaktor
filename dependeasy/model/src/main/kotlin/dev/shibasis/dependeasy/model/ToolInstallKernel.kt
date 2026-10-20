package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.toolchain.ToolArchive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object ToolInstallKernel {
    fun install(spec: KernelSpec.InstallTool, warn: (String) -> Unit) {
        val checksum = requireNotNull(spec.checksum) { "Tool installation needs a checksum" }
        val directory = File(spec.installation)
        directory.mkdirs()
        FileChannel.open(directory.resolve(".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val binary = directory.resolve(spec.executable)
                val receipt = directory.resolve(".sha256")
                if (binary.canExecute() && receipt.isFile && receipt.readText() == checksum) return
                binary.parentFile.mkdirs()
                if (spec.format == "Sdk") {
                    val source = File(spec.source)
                    check(source.canExecute()) { "SDK tool is not executable: $source" }
                    check(dev.shibasis.dependeasy.model.BuildModelReader.digest(source) == checksum) { "SDK tool checksum changed: $source" }
                    Files.deleteIfExists(binary.toPath())
                    Files.createSymbolicLink(binary.toPath(), source.toPath())
                    receipt.writeText(checksum)
                    return
                }
                val archive = Files.createTempFile(directory.toPath(), "download-", ".tgz")
                val executable = Files.createTempFile(directory.toPath(), "executable-", ".tmp")
                try {
                    download(spec.source, archive, warn)
                    val digest = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(archive).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == checksum) { "Tool checksum mismatch for ${spec.source}: $actual" }
                    val prefix = spec.archivePrefix
                    fun accept(name: String, input: java.io.InputStream, mode: Int): Boolean {
                        if (prefix == null) {
                            if (name != spec.archiveEntry) return false
                            Files.copy(input, executable, StandardCopyOption.REPLACE_EXISTING)
                            return true
                        }
                        if (!name.startsWith(prefix)) return false
                        val relative = name.removePrefix(prefix)
                        val target = directory.toPath().resolve(relative).normalize()
                        require(target.startsWith(directory.toPath()) && target != directory.toPath()) { "Invalid tool archive entry: $name" }
                        target.parent.toFile().mkdirs()
                        Files.newOutputStream(target).use(input::copyTo)
                        if (mode and 0b001001001 != 0 || relative == spec.executable) target.toFile().setExecutable(true, false)
                        return relative == spec.executable
                    }
                    var found = false
                    when (ToolArchive.valueOf(spec.format)) {
                        ToolArchive.RawFile -> { Files.copy(archive, executable, StandardCopyOption.REPLACE_EXISTING); found = true }
                        ToolArchive.TarGzip -> TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
                            while (true) {
                                val entry = tar.nextEntry ?: break
                                if (entry.isFile) found = accept(entry.name, tar, entry.mode) || found
                            }
                        }
                        ToolArchive.Zip -> ZipInputStream(Files.newInputStream(archive)).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (!entry.isDirectory) found = accept(entry.name, zip, 0) || found
                            }
                        }
                    }
                    check(found) { "Tool archive lacks ${spec.archiveEntry ?: spec.executable}" }
                    if (prefix == null) {
                        executable.toFile().setExecutable(true, false)
                        Files.move(executable, binary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }
                    receipt.writeText(checksum)
                } finally { Files.deleteIfExists(archive); Files.deleteIfExists(executable) }
            }
        }
    }

    internal fun download(source: String, archive: Path, warn: (String) -> Unit) {
        for (attempt in 1..3) {
            try {
                URI(source).toURL().openConnection().apply {
                    connectTimeout = 30_000; readTimeout = 120_000
                }.getInputStream().use { input -> Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING) }
                return
            } catch (failure: IOException) {
                if (attempt == 3 || Thread.currentThread().isInterrupted) throw failure
                warn("Tool download attempt $attempt failed; retrying: ${failure.message}")
                Thread.sleep(attempt * 1_000L)
            }
        }
    }
}
