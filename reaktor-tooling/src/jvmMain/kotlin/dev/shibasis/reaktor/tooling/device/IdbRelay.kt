package dev.shibasis.reaktor.tooling.device

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class IdbRelay(
    private val companion: String,
    private val udid: String,
    private val remotePort: Int,
    requestedPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(requestedPort, 8, InetAddress.getLoopbackAddress())
    private val links = ConcurrentHashMap.newKeySet<Pair<Socket, Process>>()
    val localPort: Int = server.localPort

    init {
        thread(isDaemon = true, name = "idb-forward-$localPort") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                open(socket)
            }
        }
    }

    private fun open(socket: Socket) {
        val process = runCatching {
            ProcessBuilder(companion, "--forward", "$udid:$remotePort")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse {
            socket.close()
            return
        }
        val link = socket to process
        links += link
        val end = {
            if (links.remove(link)) {
                runCatching { socket.close() }
                process.destroy()
            }
        }
        thread(isDaemon = true) { pump(socket.getInputStream(), process.outputStream); end() }
        thread(isDaemon = true) { pump(process.inputStream, socket.getOutputStream()); end() }
    }

    private fun pump(from: InputStream, to: OutputStream) = runCatching {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = from.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
            to.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
        links.toList().forEach { (socket, process) ->
            runCatching { socket.close() }
            process.destroy()
        }
        links.clear()
    }
}
