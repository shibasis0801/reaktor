package dev.shibasis.reaktor.io.adapters

import android.content.Context
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Files in the app's own sandbox.
 *
 * Takes any [Context] and keeps the application's, never the one it was handed. An adapter holds
 * its controller weakly, so one built from an Activity empties when that Activity is collected —
 * after a rotation, or once the app is backgrounded — and everything it does through the controller
 * afterwards silently does nothing. Keeping the application's context also means a graph that
 * outlives every screen can be built where Android starts the process: an `Application`, or a
 * `Service` the system launched with no Activity at all.
 */
class AndroidFileAdapter(context: Context): FileAdapter<Context>(context.applicationContext) {
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
