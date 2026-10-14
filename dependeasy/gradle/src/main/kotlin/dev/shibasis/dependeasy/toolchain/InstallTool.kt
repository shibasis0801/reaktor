package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ToolRef
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import java.util.zip.ZipInputStream
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
abstract class InstallTool : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.InstallTool(url.orNull ?: sdkBinary.get().asFile.absolutePath, checksum.orNull, binaryName.get(), destination.get().asFile.absolutePath, if (sdkBinary.isPresent) "Sdk" else format.get().name, archiveEntry.orNull, archivePrefix.orNull)

    @get:Input @get:Optional abstract val url: Property<String>
    @get:Input abstract val checksum: Property<String>
    @get:Input @get:Optional abstract val archiveEntry: Property<String>
    @get:Input abstract val binaryName: Property<String>
    @get:Input @get:Optional abstract val archivePrefix: Property<String>
    @get:Input abstract val format: Property<ToolArchive>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val sdkBinary: RegularFileProperty
    init { format.convention(ToolArchive.TarGzip) }
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun install() {
        val directory = destination.get().asFile
        directory.mkdirs()
        FileChannel.open(directory.resolve(".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val binary = directory.resolve(binaryName.get())
                val receipt = directory.resolve(".sha256")
                if (binary.canExecute() && receipt.isFile && receipt.readText() == checksum.get()) return
                binary.parentFile.mkdirs()
                if (sdkBinary.isPresent) {
                    val source = sdkBinary.get().asFile
                    check(source.canExecute()) { "SDK tool is not executable: $source" }
                    check(dev.shibasis.dependeasy.model.BuildModelReader.digest(source) == checksum.get()) { "SDK tool checksum changed: $source" }
                    Files.deleteIfExists(binary.toPath())
                    Files.createSymbolicLink(binary.toPath(), source.toPath())
                    receipt.writeText(checksum.get())
                    return
                }
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
                    val prefix = archivePrefix.orNull
                    fun accept(name: String, input: java.io.InputStream, mode: Int): Boolean {
                        if (prefix == null) {
                            if (name != archiveEntry.get()) return false
                            Files.copy(input, executable, StandardCopyOption.REPLACE_EXISTING)
                            return true
                        }
                        if (!name.startsWith(prefix)) return false
                        val relative = name.removePrefix(prefix)
                        val target = directory.toPath().resolve(relative).normalize()
                        require(target.startsWith(directory.toPath()) && target != directory.toPath()) { "Invalid tool archive entry: $name" }
                        target.parent.toFile().mkdirs()
                        Files.newOutputStream(target).use(input::copyTo)
                        if (mode and 0b001001001 != 0 || relative == binaryName.get()) target.toFile().setExecutable(true, false)
                        return relative == binaryName.get()
                    }
                    var found = false
                    when (format.get()) {
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
                    check(found) { "Tool archive lacks ${archiveEntry.orNull ?: binaryName.get()}" }
                    if (prefix == null) {
                        executable.toFile().setExecutable(true, false)
                        Files.move(executable, binary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }
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

enum class ToolArchive { TarGzip, Zip }
