package dev.shibasis.reaktor.conductor.store

import dev.shibasis.reaktor.conductor.ThreadDocument
import dev.shibasis.reaktor.conductor.decodeThread
import dev.shibasis.reaktor.conductor.encode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class FileThreadStore private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    fun load(): ThreadDocument? {
        check(lock.isValid) { "Thread store is closed" }
        return if (Files.exists(path)) decodeThread(Files.readString(path)) else null
    }

    fun checkpoint(document: ThreadDocument) {
        check(lock.isValid) { "Thread store is closed" }
        val temporary = Files.createTempFile(path.parent, ".conductor-", ".json")
        try {
            FileChannel.open(temporary, WRITE).use { output ->
                val bytes = ByteBuffer.wrap(document.encode().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) output.write(bytes)
                output.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        try { lock.release() } finally { channel.close() }
    }

    companion object {
        fun open(path: Path): FileThreadStore {
            val canonical = path.toFile().canonicalFile.toPath()
            Files.createDirectories(canonical.parent)
            val channel = FileChannel.open(canonical.resolveSibling("${canonical.fileName}.lock"), CREATE, WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                checkNotNull(lock) { "Thread is already in use: $canonical" }
                return FileThreadStore(canonical, channel, lock)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
}
