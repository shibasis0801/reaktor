package dev.shibasis.reaktor.conductor.workspace

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID

class ThreadInbox(val directory: Path = Path.of(System.getProperty("user.home"), ".reaktor", "inbox")) {
    fun file(thread: String): Path = directory.resolve("${thread.replace(':', '-')}.txt")

    fun write(thread: String, text: String, opening: String = "") {
        privateDirectory(directory)
        val file = file(thread)
        Files.writeString(file, (if (Files.exists(file)) "" else opening) + text, CREATE, APPEND)
        if (Files.size(file) > MaxChars) Files.writeString(file, Files.readString(file).takeLast(MaxChars))
    }

    fun waiting(runtime: String): Boolean = runCatching {
        Files.newDirectoryStream(directory, "${runtime.removeSuffix(":")}-*.{txt,txt.sent}").use { it.any() }
    }.getOrDefault(false)

    fun take(thread: String): String? {
        val taken = moved(thread) ?: return null
        return try { Files.readString(taken).trim().ifEmpty { null } }
        finally { Files.move(taken, read(thread), ATOMIC_MOVE, REPLACE_EXISTING) }
    }

    fun lend(thread: String): String? {
        val lent = lent(thread)
        moved(thread)?.let { taken ->
            try { Files.writeString(lent, Files.readString(taken), CREATE, APPEND) } finally { Files.deleteIfExists(taken) }
        }
        return runCatching { Files.readString(lent) }.getOrNull()?.trim()?.ifEmpty { null }
    }

    fun settle(thread: String) {
        try { Files.move(lent(thread), read(thread), ATOMIC_MOVE, REPLACE_EXISTING) } catch (_: NoSuchFileException) {}
    }

    private fun moved(thread: String): Path? {
        val taken = directory.resolve("${file(thread).fileName}.${UUID.randomUUID()}")
        return try { Files.move(file(thread), taken, ATOMIC_MOVE); taken } catch (_: NoSuchFileException) { null }
    }

    private fun lent(thread: String): Path = directory.resolve("${file(thread).fileName}.sent")

    private fun read(thread: String): Path = directory.resolve("${file(thread).fileName}.read")

    companion object {
        const val MaxChars = 8_000
    }
}
