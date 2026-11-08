package dev.shibasis.reaktor.cluster.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

object ControlFiles {
    private const val MaxBytes = 1_048_576L

    fun read(path: Path): JsonObject {
        require(Files.exists(path, NOFOLLOW_LINKS)) { "Missing control file $path" }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && owned(path) && Files.size(path) <= MaxBytes) { "Control file $path is not an owned, bounded file" }
        return Json.parseToJsonElement(Files.readString(path)) as? JsonObject ?: throw IllegalArgumentException("Control file $path is not an object")
    }

    fun write(path: Path, value: JsonObject) {
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID().toString().replace("-", "")}.tmp")
        try {
            Files.newByteChannel(temporary, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                .use { it.write(ByteBuffer.wrap("$value\n".toByteArray())) }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun ownedDirectory(path: Path): Path {
        require(path.isAbsolute && Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path && owned(path)) {
            "Control directory $path is not an owned canonical directory"
        }
        return path
    }

    private fun owned(path: Path) = Files.getOwner(path, NOFOLLOW_LINKS).name == System.getProperty("user.name")
}
