package dev.shibasis.reaktor.board

import dev.shibasis.reaktor.service.Response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BoardPacket(val token: Long, val part: Int, val count: Int, val data: String) : Response()

class BoardTransport(private val source: BoardSource) {
    private val mutex = Mutex()
    private var snapshot: BoardSnapshot? = null
    private var sequence = 0L
    private var current = emptyList<BoardPacket>()
    private val retained = linkedMapOf<Long, List<BoardPacket>>()
    val changes: Flow<BoardPacket> = source.changes.transform { value -> packets(value).forEach { emit(it) } }

    suspend fun read(request: BoardRead): BoardPacket {
        if (request.part > 0) mutex.withLock { retained[request.token]?.getOrNull(request.part) }?.let { return it }
        return packets(source.read()).first()
    }

    private suspend fun packets(value: BoardSnapshot): List<BoardPacket> = mutex.withLock {
        if (value != snapshot) {
            val text = BoardWire.json.encodeToString(BoardSnapshot.serializer(), value)
            val parts = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + BoardWire.PartSize, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                parts += text.substring(start, end)
                start = end
            }
            val token = ++sequence
            current = parts.mapIndexed { index, data -> BoardPacket(token, index, parts.size, data) }
            snapshot = value
            retained[token] = current
            while (retained.size > 2) retained.remove(retained.keys.first())
        }
        current
    }
}

class BoardAssembly {
    private var token: Long? = null
    private var count = 0
    private val parts = ArrayList<String>()

    fun accept(packet: BoardPacket): BoardSnapshot? {
        if (packet.part == 0) {
            token = packet.token
            count = packet.count
            parts.clear()
        }
        if (packet.token != token || packet.count != count || packet.part != parts.size ||
            packet.count <= 0 || packet.part !in 0 until packet.count || packet.data.length > BoardWire.PartSize) {
            token = null
            parts.clear()
            return null
        }
        parts += packet.data
        if (parts.size != count) return null
        val text = parts.joinToString("")
        token = null
        parts.clear()
        return BoardWire.json.decodeFromString(BoardSnapshot.serializer(), text)
    }
}

private object BoardWire {
    const val PartSize = 8_000
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
}
