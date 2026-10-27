package dev.shibasis.reaktor.io.adapters

import android.app.Activity
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class AndroidFileAdapter(activity: Activity): FileAdapter<Activity>(activity) {
    override val cacheDirectory = controller?.cacheDir?.absolutePath ?: ""
    override val documentDirectory = controller?.filesDir?.absolutePath ?: ""

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
        return input.use { source ->
            source.readByteArray()
        }
    }

    override suspend fun writeBinaryFile(path: String, data: ByteArray) {
        ensureParentDirectory(path)
        val output = SystemFileSystem.sink(Path(path)).buffered()
        output.use { sink ->
            sink.write(data)
        }
    }
}
