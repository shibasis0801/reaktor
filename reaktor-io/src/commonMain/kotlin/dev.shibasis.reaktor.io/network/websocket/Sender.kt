package dev.shibasis.reaktor.io.network.websocket

import dev.shibasis.reaktor.io.network.SocketEventKind
import dev.shibasis.reaktor.io.network.SocketObservation
import io.ktor.client.plugins.websocket.converter
import io.ktor.util.reflect.typeInfo
import io.ktor.utils.io.charsets.Charsets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send

class Sender(
    webSocket: WebSocket
): Listener(webSocket) {
    suspend fun send(frame: Frame) = withSession { send(frame) }.also { if (it.isSuccess) sent(frame) }
    suspend fun send(byteArray: ByteArray) = withSession { send(byteArray) }.also { if (it.isSuccess) sent(Frame.Binary(true, byteArray)) }
    suspend fun send(content: String) = withSession { send(content) }.also { if (it.isSuccess) sent(Frame.Text(content)) }
    suspend inline fun <reified T> send(data: T) = withSession {
        val frame = requireNotNull(converter) { "No WebSocket content converter is installed" }.serialize(Charsets.UTF_8, typeInfo<T>(), data)
        outgoing.send(frame)
        frame
    }.also { result -> result.getOrNull()?.let(::sent) }

    @PublishedApi
    internal fun sent(frame: Frame) {
        when (frame) {
            is Frame.Text -> SocketObservation.publish(webSocket.observedConnection, webSocket.observedUrl, SocketEventKind.Sent,
                text = frame.readText(), bytes = frame.data.size.toLong())
            is Frame.Binary -> SocketObservation.publish(webSocket.observedConnection, webSocket.observedUrl, SocketEventKind.Sent,
                bytes = frame.data.size.toLong(), binary = true)
            else -> Unit
        }
    }
}
