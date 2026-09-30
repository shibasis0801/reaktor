package dev.shibasis.reaktor.devtools

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter

private object AndroidPlatformInfo : DevToolsPlatformInfo {
    override val name: String = "Android"
    override val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    override val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override val frameVitalsFidelity: Fidelity = Fidelity.Stream
    override val memoryVitalsFidelity: Fidelity = Fidelity.Stream

    override fun epochMillis(): Long = System.currentTimeMillis()

    override fun mirrorLog(level: LogLevel, subsystem: String, message: String) {
        val priority = when (level) {
            LogLevel.Verbose -> Log.VERBOSE
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
            LogLevel.Assert -> Log.ASSERT
        }
        Log.println(priority, subsystem, message)
    }
}

actual fun devToolsPlatformInfo(): DevToolsPlatformInfo = AndroidPlatformInfo

/**
 * Android binds an abstract socket rather than a TCP port.
 *
 * `adb forward tcp:<port> localabstract:reaktor-devtools` reaches it, which means the app needs no
 * network permission, cannot collide with a port another app claimed, and is unreachable from the
 * network entirely. It is also the same mechanism the platform's own tooling uses, so it survives
 * doze, background limits and the app being moved between users.
 */
actual fun devToolsTransport(port: Int): AgentTransport =
    LocalSocketTransport(DevToolsProtocol.AndroidSocketName)

class LocalSocketTransport(private val socketName: String) : AgentTransport {
    private var server: LocalServerSocket? = null

    override val description: String
        get() = "localabstract:$socketName"

    override suspend fun accept(): PeerChannel = withContext(Dispatchers.IO) {
        val listener = server ?: LocalServerSocket(socketName).also { server = it }
        LocalSocketPeerChannel(listener.accept())
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
    }
}

private class LocalSocketPeerChannel(private val socket: LocalSocket) : PeerChannel {
    private val reader: BufferedReader = socket.inputStream.bufferedReader()
    private val writer: BufferedWriter = socket.outputStream.bufferedWriter()
    private val writeLock = Mutex()

    override val incoming: Flow<String> = flow {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) emit(line)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(message: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            writer.write(message)
            writer.write("\n")
            writer.flush()
        }
    }

    override suspend fun close() {
        runCatching { socket.close() }
    }
}
