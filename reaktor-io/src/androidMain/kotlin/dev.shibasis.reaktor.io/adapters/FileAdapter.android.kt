package dev.shibasis.reaktor.io.adapters

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

internal actual suspend fun platformFileExists(path: String): Boolean =
    SystemFileSystem.exists(Path(path))

internal actual suspend fun platformDeleteFile(path: String) {
    val target = Path(path)
    if (SystemFileSystem.exists(target)) {
        SystemFileSystem.delete(target, false)
    }
}

internal actual suspend fun platformCopyFile(
    sourcePath: String,
    destPath: String,
) {
    val source = Path(sourcePath)
    if (!SystemFileSystem.exists(source)) {
        return
    }

    val input = SystemFileSystem.source(source).buffered()
    val output = SystemFileSystem.sink(Path(destPath)).buffered()
    try {
        val buffer = ByteArray(8192)
        while (true) {
            val bytesRead = input.readAtMostTo(buffer)
            if (bytesRead <= 0) {
                break
            }
            output.write(buffer, 0, bytesRead)
        }
    } finally {
        input.close()
        output.close()
    }
}

internal actual suspend fun platformReadBinary(path: String): ByteArray? {
    val target = Path(path)
    if (!SystemFileSystem.exists(target)) {
        return null
    }

    val input = SystemFileSystem.source(target).buffered()
    return input.use { input ->
        input.readByteArray()
    }
}

internal actual suspend fun platformReadText(path: String): String? {
    val target = Path(path)
    if (!SystemFileSystem.exists(target)) {
        return null
    }

    val input = SystemFileSystem.source(target).buffered()
    return input.use { input ->
        input.readString()
    }
}

internal actual suspend fun platformWriteText(
    path: String,
    data: String,
) {
    val output = SystemFileSystem.sink(Path(path)).buffered()
    output.use { output ->
        output.writeString(data)
    }
}

internal actual suspend fun platformWriteBinary(
    path: String,
    data: ByteArray,
) {
    val output = SystemFileSystem.sink(Path(path)).buffered()
    output.use { output ->
        output.write(data)
    }
}
