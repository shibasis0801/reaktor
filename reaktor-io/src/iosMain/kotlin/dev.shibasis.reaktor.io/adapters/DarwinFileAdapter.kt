package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.io.adapters.FileAdapter
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

class DarwinFileAdapter(): FileAdapter<Unit>(Unit) {
    override val cacheDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )!!.path.orEmpty()

    override val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )!!.path.orEmpty()

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
        val output = SystemFileSystem.sink(Path(path)).buffered()
        try {
            output.write(data)
        } finally {
            output.close()
        }
    }
}
