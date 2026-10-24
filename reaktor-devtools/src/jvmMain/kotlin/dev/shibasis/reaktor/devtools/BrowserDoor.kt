package dev.shibasis.reaktor.devtools

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.RawWebSocket
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

data class BrowserVisit(
    val tab: String,
    val browser: String,
    val page: String,
    val connected: Boolean,
    internal val connection: Long,
) {
    val name: String get() = listOf(browser, page).filter(String::isNotBlank).joinToString(" · ")
}

class BrowserDoor(
    private val scope: CoroutineScope,
    private val requestedPort: Int = DevToolsProtocol.BrowserPort,
    private val admits: (origin: String) -> Boolean = ::loopbackPage,
) : AutoCloseable {
    private val selector = SelectorManager(Dispatchers.IO)
    private val opening = Mutex()
    private val connections = AtomicLong()
    private val arrivals = MutableStateFlow<Map<String, PeerChannel>>(emptyMap())
    private val seen = MutableStateFlow<Map<String, BrowserVisit>>(emptyMap())
    private var server: ServerSocket? = null
    private var listening: Job? = null

    val visits: StateFlow<Map<String, BrowserVisit>> = seen

    var port: Int? = null
        private set

    suspend fun open(): Int = opening.withLock {
        port?.let { return@withLock it }
        val bound = aSocket(selector).tcp().bind("127.0.0.1", requestedPort) { reuseAddress = true }
        server = bound
        listening = scope.launch {
            while (isActive) {
                val socket = runCatching { bound.accept() }.getOrNull() ?: break
                launch { welcome(socket) }
            }
        }
        (bound.localAddress as InetSocketAddress).port.also { port = it }
    }

    suspend fun take(tab: String): PeerChannel {
        while (true) {
            arrivals.first { tab in it }
            var taken: PeerChannel? = null
            arrivals.update { current -> taken = current[tab]; if (taken == null) current else current - tab }
            taken?.let { return it }
        }
    }

    @OptIn(InternalAPI::class)
    private suspend fun welcome(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        val request = withTimeoutOrNull(HandshakeMillis) { input.readHandshake() }
        val refusal = request?.refusal(admits) ?: "400 Bad Request"
        if (request == null || refusal.isNotEmpty()) {
            runCatching { output.writeStringUtf8("HTTP/1.1 $refusal\r\nConnection: close\r\nContent-Length: 0\r\n\r\n") }
            socket.close()
            return
        }
        output.writeStringUtf8(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${request.accept()}\r\n\r\n",
        )
        val session = DefaultWebSocketSession(
            RawWebSocket(input, output, MaxFrameBytes, masking = false, coroutineContext = scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
            PingMillis,
            PongTimeoutMillis,
        )
        session.start(emptyList())
        val channel = DoorChannel(session, socket)
        val connection = connections.incrementAndGet()
        val tab = request.tab
        var replaced: PeerChannel? = null
        arrivals.update { current -> replaced = current[tab]; current + (tab to channel) }
        replaced?.let { stale -> scope.launch { stale.close() } }
        seen.update { it + (tab to BrowserVisit(tab, request.browser(), request.page(), connected = true, connection = connection)) }
        runCatching { session.closeReason.await() }
        arrivals.update { current -> if (current[tab] === channel) current - tab else current }
        seen.update { current -> current[tab]?.takeIf { it.connection == connection }?.let { current + (tab to it.copy(connected = false)) } ?: current }
        delay(ReturnGraceMillis)
        seen.update { current -> if (current[tab]?.let { !it.connected && it.connection == connection } == true) current - tab else current }
    }

    override fun close() {
        listening?.cancel()
        runCatching { server?.close() }
        server = null
        port = null
        val open = arrivals.value.values
        arrivals.value = emptyMap()
        seen.value = emptyMap()
        scope.launch { open.forEach { runCatching { it.close() } } }
        selector.close()
    }
}

fun loopbackPage(origin: String): Boolean {
    val host = runCatching { URI(origin).host }.getOrNull()?.removeSurrounding("[", "]")?.lowercase() ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")
}

private class DoorChannel(private val session: DefaultWebSocketSession, private val socket: Socket) : PeerChannel {
    override val incoming: Flow<String> = session.incoming.receiveAsFlow()
        .filterIsInstance<Frame.Text>()
        .map { it.readText() }
        .catch { }

    override suspend fun send(message: String) = session.outgoing.send(Frame.Text(message))

    override suspend fun close() {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "Workbench closed the connection")) }
        runCatching { socket.close() }
    }
}

private class Handshake(private val target: String, private val headers: Map<String, String>) {
    private val uri = runCatching { URI(target) }.getOrNull()

    val tab: String = uri?.rawQuery.orEmpty().split('&')
        .map { it.substringBefore('=') to it.substringAfter('=', "") }
        .firstOrNull { it.first == DevToolsProtocol.BrowserTabParameter }?.second
        ?.takeIf { TabPattern.matches(it) }
        ?: "tab-${System.nanoTime().toString(36)}"

    fun refusal(admits: (String) -> Boolean): String = when {
        uri?.path != DevToolsProtocol.BrowserPath -> "404 Not Found"
        !headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true) -> "400 Bad Request"
        headers["connection"].orEmpty().split(',').none { it.trim().equals("upgrade", ignoreCase = true) } -> "400 Bad Request"
        headers["sec-websocket-version"] != "13" -> "426 Upgrade Required"
        headers["sec-websocket-key"].isNullOrBlank() -> "400 Bad Request"
        headers["origin"]?.let(admits) == false -> "403 Forbidden"
        else -> ""
    }

    fun accept(): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((headers.getValue("sec-websocket-key").trim() + WebSocketGuid).toByteArray()),
    )

    fun page(): String = headers["origin"]?.let { runCatching { URI(it).authority }.getOrNull() }.orEmpty()

    fun browser(): String {
        val agent = headers["user-agent"].orEmpty()
        return when {
            "Edg/" in agent -> "Edge"
            "OPR/" in agent -> "Opera"
            "Firefox/" in agent -> "Firefox"
            "Chrome/" in agent -> "Chrome"
            "Safari/" in agent -> "Safari"
            agent.startsWith("node", ignoreCase = true) -> "Node"
            else -> "Browser"
        }
    }
}

private suspend fun ByteReadChannel.readHandshake(): Handshake? {
    val request = readUTF8Line(MaxLineBytes)?.split(' ') ?: return null
    if (request.size != 3 || request[0] != "GET") return null
    val headers = HashMap<String, String>()
    repeat(MaxHeaders) {
        val line = readUTF8Line(MaxLineBytes) ?: return null
        if (line.isEmpty()) return Handshake(request[1], headers)
        val colon = line.indexOf(':')
        if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return null
}

private val TabPattern = Regex("[A-Za-z0-9-]{1,64}")
private const val WebSocketGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val HandshakeMillis = 5_000L
private const val PingMillis = 15_000L
private const val PongTimeoutMillis = 30_000L
private const val ReturnGraceMillis = 20_000L
private const val MaxFrameBytes = 32L * 1024 * 1024
private const val MaxLineBytes = 8 * 1024
private const val MaxHeaders = 64
