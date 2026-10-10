package dev.shibasis.dependeasy.desktop

import org.gradle.api.GradleException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal class RuntimeJarStore(private val directory: File, private val downloadedArtifacts: File) {
    fun acquire(classpath: Collection<File>): RuntimeLease {
        val jars = directory.resolve("jars").apply { mkdirs() }
        val launches = directory.resolve("launches").apply { mkdirs() }
        return FileChannel.open(directory.resolve("image.lock").toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val entries = classpath.map { if (it.startsWith(downloadedArtifacts)) it else retain(it, jars) }
                val manifest = launches.resolve("${UUID.randomUUID()}.classpath").apply { writeText(entries.joinToString("\n")) }
                val lease = RuntimeLease(entries, manifest, FileChannel.open(manifest.toPath(), WRITE).also { it.lock() })
                collect(launches, jars, manifest)
                lease
            }
        }
    }

    private fun retain(source: File, jars: File): File {
        require(source.isFile) { "Stable runtime requires jars: $source" }
        val cached = jars.resolve("${source.digest()}.jar")
        if (cached.isFile) return cached
        val staging = File.createTempFile("staging-", ".part", jars)
        try {
            source.copyTo(staging, overwrite = true)
            runCatching { ZipFile(staging).use { } }.getOrElse {
                throw GradleException("$source changed while starting the app. Run it again.", it)
            }
            val retained = jars.resolve("${staging.digest()}.jar")
            if (!retained.exists()) Files.move(staging.toPath(), retained.toPath(), ATOMIC_MOVE)
            return retained
        } finally {
            staging.delete()
        }
    }

    private fun collect(launches: File, jars: File, current: File) {
        val live = launches.listFiles().orEmpty().filter { it == current || !it.abandoned() }
        launches.listFiles().orEmpty().filter { it !in live }.forEach { it.delete() }
        val used = live.flatMap { it.readLines() }.map(::File).filter { it.parentFile == jars }.toSet()
        jars.listFiles().orEmpty().filter { it !in used }.forEach { it.delete() }
    }
}

private fun File.digest(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun File.abandoned(): Boolean = try {
    FileChannel.open(toPath(), WRITE).use { channel -> channel.tryLock()?.let { it.release(); true } ?: false }
} catch (_: OverlappingFileLockException) {
    false
} catch (_: java.io.IOException) {
    true
}

internal class RuntimeLease(val entries: List<File>, private val manifest: File, private val channel: FileChannel) : AutoCloseable {
    override fun close() { channel.close(); manifest.delete() }
}
