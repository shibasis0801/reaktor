package dev.shibasis.reaktor.conductor.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun privateDirectory(path: Path) {
    Files.createDirectories(path)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
}

internal fun atomicWrite(path: Path, text: String) {
    val temporary = Files.createTempFile(path.parent, ".agent-", ".json")
    try {
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        FileChannel.open(temporary, WRITE).use { file ->
            val bytes = ByteBuffer.wrap(text.toByteArray())
            while (bytes.hasRemaining()) file.write(bytes)
            file.force(true)
        }
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}
