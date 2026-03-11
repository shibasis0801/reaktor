package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Feature
import java.io.File
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class DesktopFileAdapter(
    rootDirectory: String = File(System.getProperty("user.home"), ".reaktor").absolutePath,
    override val cacheDirectory: String = "$rootDirectory/cache",
    override val documentDirectory: String = "$rootDirectory/documents",
) : FileAdapter<Unit>(Unit) {
    init {
        ensureDirectory(cacheDirectory)
        ensureDirectory(documentDirectory)
    }

    override suspend fun exists(path: String): Boolean =
        SystemFileSystem.exists(Path(path))

    override suspend fun delete(path: String) {
        val target = Path(path)
        if (SystemFileSystem.exists(target)) {
            SystemFileSystem.delete(target, false)
        }
    }

    override suspend fun readBinaryFile(path: String): ByteArray? {
        val target = Path(path)
        if (!SystemFileSystem.exists(target)) {
            return null
        }

        val input = SystemFileSystem.source(target).buffered()
        return try {
            input.readByteArray()
        } finally {
            input.close()
        }
    }

    override suspend fun writeBinaryFile(path: String, data: ByteArray) {
        ensureParentDirectory(path)
        val output = SystemFileSystem.sink(Path(path)).buffered()
        try {
            output.write(data)
        } finally {
            output.close()
        }
    }
}

fun Feature.desktopFiles(
    rootDirectory: String = File(System.getProperty("user.home"), ".reaktor").absolutePath,
    cacheDirectory: String = "$rootDirectory/cache",
    documentDirectory: String = "$rootDirectory/documents",
) {
    File = DesktopFileAdapter(
        rootDirectory = rootDirectory,
        cacheDirectory = cacheDirectory,
        documentDirectory = documentDirectory,
    )
}

private fun ensureDirectory(path: String) {
    val directory = Path(path)
    if (!SystemFileSystem.exists(directory)) {
        SystemFileSystem.createDirectories(directory, false)
    }
}

private fun ensureParentDirectory(path: String) {
    val target = Path(path)
    target.parent?.toString()?.let(::ensureDirectory)
}
