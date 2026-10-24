package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class BrowserDialTransport(private val port: Int) : AgentTransport {
    private var current: BrowserChannel? = null
    private var failures = 0

    override val description: String
        get() = "dials the workbench at ${address()}"

    override suspend fun accept(): PeerChannel {
        current?.closed?.await()
        current = null
        if (failures > 0) delay(RedialMillis * failures.coerceAtMost(MaxRedialSteps))
        val channel = BrowserChannel(BrowserSocket(address()))
        return try {
            channel.opened.await()
            failures = 0
            channel.also { current = it }
        } catch (failure: Throwable) {
            failures++
            channel.abandon()
            throw failure
        }
    }

    override fun close() {
        current?.abandon()
        current = null
    }

    private fun address(): String =
        "ws://127.0.0.1:$port${DevToolsProtocol.BrowserPath}?${DevToolsProtocol.BrowserTabParameter}=${BrowserTab.id}"
}

private class BrowserChannel(private val socket: BrowserSocket) : PeerChannel {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    val opened = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()

    override val incoming: Flow<String> = inbox.receiveAsFlow()

    init {
        socket.onopen = { opened.complete(Unit) }
        socket.onmessage = { event -> (event.data as? String)?.let { inbox.trySend(it) } }
        socket.onclose = {
            opened.completeExceptionally(IllegalStateException("No workbench answered at ${socket.url}"))
            inbox.close()
            closed.complete(Unit)
        }
    }

    override suspend fun send(message: String) {
        check(socket.readyState == SocketOpen) { "The workbench connection is closed" }
        socket.send(message)
    }

    override suspend fun close() = abandon()

    fun abandon() {
        if (socket.readyState < SocketClosing) socket.close()
    }
}

@OptIn(ExperimentalUuidApi::class)
private object BrowserTab {
    val id: String by lazy {
        runCatching {
            val storage = js("globalThis.sessionStorage")
            (storage.getItem(StorageKey) as String?) ?: Uuid.random().toString().also { storage.setItem(StorageKey, it) }
        }.getOrElse { Uuid.random().toString() }
    }
}

@JsName("WebSocket")
private external class BrowserSocket(url: String) {
    val url: String
    val readyState: Int
    var onopen: ((dynamic) -> Unit)?
    var onmessage: ((BrowserMessage) -> Unit)?
    var onclose: ((dynamic) -> Unit)?
    fun send(data: String)
    fun close()
}

private external interface BrowserMessage {
    val data: Any?
}

private const val SocketOpen = 1
private const val SocketClosing = 2
private const val RedialMillis = 2_000L
private const val MaxRedialSteps = 15
private const val StorageKey = "reaktor.devtools.tab"
