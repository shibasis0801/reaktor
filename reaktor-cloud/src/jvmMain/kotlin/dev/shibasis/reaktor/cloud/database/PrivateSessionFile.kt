package dev.shibasis.reaktor.cloud.database

import java.io.File
import java.nio.file.Files

fun checkPrivateFile(file: File, empty: Boolean) {
    require(file.isAbsolute && file.isFile && !Files.isSymbolicLink(file.toPath())) { "Invalid private database session file" }
    if (empty) require(file.length() == 0L) { "Database result destination must be empty" }
    listOf(file, file.parentFile).forEach { path ->
        require(!Files.isSymbolicLink(path.toPath()))
        val permissions = runCatching { Files.getPosixFilePermissions(path.toPath()) }.getOrNull()
        require(permissions == null || permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "Database session path must be owner-only" }
    }
}
