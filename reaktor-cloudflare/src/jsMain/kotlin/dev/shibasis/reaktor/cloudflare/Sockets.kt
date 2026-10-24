package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.socket
import dev.shibasis.reaktor.io.network.websocket.Heartbeat
import dev.shibasis.reaktor.service.contract.Channel
import dev.shibasis.reaktor.service.contract.SocketSession

class PartySockets(
    private val graph: Graph,
    private val exposed: List<Channel<*>> = emptyList(),
    private val heartbeat: Heartbeat? = Heartbeat(),
) {
    private val sessions = mutableMapOf<String, SocketSession>()

    val connections: Int get() = sessions.size

    suspend fun message(message: PartyServerMessage, sender: PartyServerConnection, cloudflare: CloudflareContext? = null) {
        val text = message.textOrNull() ?: return
        if (heartbeat != null && text == heartbeat.ping) return sender.send(heartbeat.pong)
        val session = sessions.getOrPut(sender.id) {
            graph.socket(sender.id, exposed, cloudflare?.let { mapOf(CloudflareContextAttribute to it) }.orEmpty()) { sender.send(it) }
        }
        session.receive(text)
    }

    fun close(connection: PartyServerConnection) {
        sessions.remove(connection.id)?.close()
    }
}
