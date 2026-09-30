package dev.shibasis.reaktor.io.network

import io.ktor.util.date.GMTDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class SocketEventKind { Opened, Sent, Received, Closed, Failed }

data class SocketEvent(
    val connection: String,
    val url: String,
    val kind: SocketEventKind,
    val text: String?,
    val bytes: Long,
    val binary: Boolean,
    val heartbeat: Boolean,
    val code: Int?,
    val atMillis: Long,
)

fun interface SocketObserver {
    fun observe(event: SocketEvent)
}

object SocketObservation {
    private val observers = MutableStateFlow(emptyList<SocketObserver>())
    private val connections = MutableStateFlow(0L)

    val active: Boolean get() = observers.value.isNotEmpty()

    var textLimit: Int = 16 * 1024

    fun observe(observer: SocketObserver): () -> Unit {
        observers.update { it + observer }
        return { observers.update { current -> current - observer } }
    }

    internal fun nextConnection(): String = "ws-${connections.updateAndGet { it + 1 }}"

    internal fun publish(
        connection: String?,
        url: String?,
        kind: SocketEventKind,
        text: String? = null,
        bytes: Long = text?.encodeToByteArray()?.size?.toLong() ?: 0,
        binary: Boolean = false,
        heartbeat: Boolean = false,
        code: Int? = null,
    ) {
        if (!active || connection == null || url == null) return
        val event = SocketEvent(connection, url, kind, text?.take(textLimit), bytes, binary, heartbeat, code, GMTDate().timestamp)
        observers.value.forEach { runCatching { it.observe(event) } }
    }
}

private fun MutableStateFlow<Long>.updateAndGet(function: (Long) -> Long): Long {
    while (true) {
        val previous = value
        val next = function(previous)
        if (compareAndSet(previous, next)) return next
    }
}
