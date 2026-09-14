package dev.shibasis.reaktor.devtools

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import dev.shibasis.reaktor.core.framework.Async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A loopback TCP listener.
 *
 * Bound to 127.0.0.1 without exception. A developer tool that accepts connections from the network
 * is a remote-control surface on a device someone carries around; reaching it from a desktop is
 * the forwarding tool's job — `idb forward`, usbmux, or nothing at all on a simulator, which
 * shares the host's loopback already.
 */
class TcpAgentTransport(private val port: Int) : AgentTransport {
    private val selector = SelectorManager(Dispatchers.Async)
    private var socket: ServerSocket? = null

    override val description: String
        get() = "tcp 127.0.0.1:$port"

    override suspend fun accept(): PeerChannel {
        val server = socket ?: aSocket(selector).tcp().bind("127.0.0.1", port).also { socket = it }
        return SocketPeerChannel(server.accept())
    }

    override fun close() {
        socket?.close()
        socket = null
        selector.close()
    }
}

/**
 * Newline-delimited JSON over a socket.
 *
 * The framing is deliberately the simplest thing that survives a partial read: messages never
 * contain a raw newline once serialised, so a line is a message. Writes are serialised through a
 * mutex because facts and replies are produced by different coroutines.
 */
class SocketPeerChannel(private val socket: Socket) : PeerChannel {
    private val read: ByteReadChannel = socket.openReadChannel()
    private val write: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private val writeLock = Mutex()

    override val incoming: Flow<String> = flow {
        while (true) {
            val line = read.readUTF8Line() ?: break
            if (line.isNotBlank()) emit(line)
        }
    }

    override suspend fun send(message: String) = writeLock.withLock {
        write.writeStringUtf8(message)
        write.writeStringUtf8("\n")
    }

    override suspend fun close() {
        runCatching { socket.close() }
    }
}
