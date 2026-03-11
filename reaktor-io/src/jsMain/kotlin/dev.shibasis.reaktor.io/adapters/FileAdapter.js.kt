package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Feature
import kotlin.js.Promise
import kotlinx.coroutines.await

class WebFileAdapter(
    override val cacheDirectory: String = "cache",
    override val documentDirectory: String = "documents",
) : FileAdapter<Unit>(Unit) {
    override suspend fun exists(path: String): Boolean =
        runCatching {
            resolveFileHandle(path, create = false)
            true
        }.getOrDefault(false)

    override suspend fun delete(path: String) {
        runCatching {
            val (directory, name) = resolveParent(path, create = false)
            awaitDynamic(directory.removeEntry(name))
        }
    }

    override suspend fun readBinaryFile(path: String): ByteArray? = runCatching {
        val fileHandle = resolveFileHandle(path, create = false)
        val file = awaitDynamic(fileHandle.getFile())
        arrayBufferToByteArray(awaitDynamic(file.arrayBuffer()))
    }.getOrNull()

    override suspend fun writeBinaryFile(path: String, data: ByteArray) {
        val fileHandle = resolveFileHandle(path, create = true)
        val writable = awaitDynamic(fileHandle.createWritable())
        try {
            awaitDynamic(writable.write(data.toUint8Array()))
        } finally {
            awaitDynamic(writable.close())
        }
    }
}

fun Feature.webFiles(
    cacheDirectory: String = "cache",
    documentDirectory: String = "documents",
) {
    File = WebFileAdapter(cacheDirectory, documentDirectory)
}

private suspend fun resolveRootDirectory(): dynamic {
    val storage = js("globalThis.navigator && globalThis.navigator.storage")
    return try {
        awaitDynamic(storage.getDirectory())
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
        directory = awaitDynamic(directory.getDirectoryHandle(segment, createOptions(create)))
    }
    return directory
}

private suspend fun resolveFileHandle(
    path: String,
    create: Boolean,
): dynamic {
    val (directory, name) = resolveParent(path, create)
    return awaitDynamic(directory.getFileHandle(name, createOptions(create)))
}

private suspend fun awaitDynamic(value: dynamic): dynamic =
    value.unsafeCast<Promise<dynamic>>().await()

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
