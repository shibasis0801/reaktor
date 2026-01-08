package dev.shibasis.reaktor.io.network.websocket

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.websocket.Frame
import io.ktor.websocket.send

class Sender(
    webSocket: WebSocket
): Listener(webSocket) {
    suspend fun send(frame: Frame) = withSession { send(frame) }
    suspend fun send(byteArray: ByteArray) = withSession { send(byteArray) }
    suspend fun send(content: String) = withSession { send(content) }
    suspend inline fun <reified T> send(data: T) = withSession { sendSerialized(data) }
}