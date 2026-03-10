package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.await

internal actual suspend fun platformFileExists(path: String): Boolean = runCatching {
    resolveFileHandle(path, create = false)
    true
}.getOrDefault(false)

internal actual suspend fun platformDeleteFile(path: String) {
    runCatching {
        val (directory, name) = resolveParent(path, create = false)
        directory.removeEntry(name).await()
    }
}

internal actual suspend fun platformCopyFile(
    sourcePath: String,
    destPath: String,
) {
    val contents = platformReadBinary(sourcePath) ?: return
    platformWriteBinary(destPath, contents)
}

internal actual suspend fun platformReadBinary(path: String): ByteArray? = runCatching {
    val fileHandle = resolveFileHandle(path, create = false)
    val file = fileHandle.getFile().await()
    arrayBufferToByteArray(file.arrayBuffer().await())
}.getOrNull()

internal actual suspend fun platformReadText(path: String): String? =
    platformReadBinary(path)?.decodeToString()

internal actual suspend fun platformWriteText(
    path: String,
    data: String,
) {
    val fileHandle = resolveFileHandle(path, create = true)
    val writable = fileHandle.createWritable().await()
    try {
        writable.write(data).await()
    } finally {
        writable.close().await()
    }
}

internal actual suspend fun platformWriteBinary(
    path: String,
    data: ByteArray,
) {
    val fileHandle = resolveFileHandle(path, create = true)
    val writable = fileHandle.createWritable().await()
    try {
        writable.write(data.toUint8Array()).await()
    } finally {
        writable.close().await()
    }
}

class WebFileAdapter(
    override val cacheDirectory: String = "cache",
    override val documentDirectory: String = "documents",
) : FileAdapter<Unit>(Unit)

fun Feature.webFiles(
    cacheDirectory: String = "cache",
    documentDirectory: String = "documents",
) {
    File = WebFileAdapter(cacheDirectory, documentDirectory)
}

private suspend fun resolveRootDirectory(): dynamic {
    val storage = js("globalThis.navigator && globalThis.navigator.storage")
    return try {
        storage.getDirectory().await()
    } catch (_: Throwable) {
        error(
            "Web File System API is unavailable in this JS runtime. " +
                "Use a browser/worker runtime with navigator.storage.getDirectory() or a different FileAdapter.",
        )
    }
}

private suspend fun resolveParent(
    path: String,
    create: Boolean,
): Pair<dynamic, String> {
    val segments = pathSegments(path)
    require(segments.isNotEmpty()) { "Path must not be empty" }
    val directory = resolveDirectory(segments.dropLast(1), create)
    return directory to segments.last()
}

private suspend fun resolveDirectory(
    segments: List<String>,
    create: Boolean,
): dynamic {
    var directory = resolveRootDirectory()
    for (segment in segments) {
        directory = directory.getDirectoryHandle(segment, createOptions(create)).await()
    }
    return directory
}

private suspend fun resolveFileHandle(
    path: String,
    create: Boolean,
): dynamic {
    val (directory, name) = resolveParent(path, create)
    return directory.getFileHandle(name, createOptions(create)).await()
}

private fun createOptions(create: Boolean): dynamic {
    val options = js("({})")
    options.create = create
    return options
}

private fun pathSegments(path: String): List<String> =
    path
        .split("/")
        .map(String::trim)
        .filter { it.isNotEmpty() && it != "." }

private fun ByteArray.toUint8Array(): dynamic {
    val view = js("new Uint8Array(this.length)")
    for (index in indices) {
        view[index] = this[index].toInt() and 0xFF
    }
    return view
}

private fun arrayBufferToByteArray(buffer: dynamic): ByteArray {
    val view = js("new Uint8Array(buffer)")
    val bytes = ByteArray(view.length as Int)
    for (index in bytes.indices) {
        bytes[index] = (view[index] as Int).toByte()
    }
    return bytes
}
