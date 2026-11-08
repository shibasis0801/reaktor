package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.contract.Rejected
import dev.shibasis.reaktor.service.contract.Route
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Serializable
class McpPost(
    val jsonrpc: String? = null,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
) : Request() {
    fun message(): JsonObject = buildJsonObject {
        jsonrpc?.let { put("jsonrpc", it) }
        id?.let { put("id", it) }
        method?.let { put("method", it) }
        params?.let { put("params", it) }
        result?.let { put("result", it) }
        error?.let { put("error", it) }
    }
}

@Serializable
class McpListen : Request()

@Serializable
class McpEnd : Request()

@Serializable(with = McpReply.Body::class)
class McpReply(val message: JsonElement?, headers: Map<String, String> = emptyMap(), status: StatusCode = StatusCode.OK) :
    Response(headers.toMutableMap(), status) {
    object Body : KSerializer<McpReply> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun serialize(encoder: Encoder, value: McpReply) = encoder.encodeSerializableValue(JsonElement.serializer(), value.message ?: JsonNull)
        override fun deserialize(decoder: Decoder): McpReply = McpReply(decoder.decodeSerializableValue(JsonElement.serializer()))
    }
}

class McpHttpArrival(val scope: String, val headers: Map<String, String>, val query: Map<String, String>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

class McpHttpScope(
    val server: ReaktorMcpServer,
    val admit: (McpHttpArrival) -> JsonObject?,
    val opened: (McpHttpSession) -> Unit = {},
    val closed: (McpHttpSession) -> Unit = {},
)

class McpHttpSession internal constructor(val id: String, val scope: String, val stamp: JsonObject, server: ReaktorMcpServer) : AutoCloseable {
    private val notices = MutableSharedFlow<JsonElement>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val listeners = AtomicInteger()
    private val calls = AtomicInteger()
    private val threads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp/$scope/${id.take(8)}/", 0).factory())
    private val dispatcher = threads.asCoroutineDispatcher()
    private val admission = Semaphore(Concurrency)
    private val connection = server.openConnection { notices.tryEmit(it) }

    @Volatile var seen: Long = System.currentTimeMillis()
        private set

    val listening: Boolean get() = listeners.get() > 0

    val idle: Boolean get() = listeners.get() == 0 && calls.get() == 0

    internal suspend fun exchange(message: JsonObject, headers: Map<String, String>): JsonElement? {
        seen = System.currentTimeMillis()
        calls.incrementAndGet()
        try { return admission.withPermit { withContext(dispatcher) { connection.handle(stamped(message).toString(), headers) } } }
        finally { calls.decrementAndGet(); seen = System.currentTimeMillis() }
    }

    internal fun notices(): Flow<JsonElement> = notices
        .onSubscription { listeners.incrementAndGet() }
        .takeWhile { it != JsonNull }
        .onCompletion { listeners.decrementAndGet(); seen = System.currentTimeMillis() }

    private fun stamped(message: JsonObject): JsonObject {
        val params = message["params"] ?: JsonObject(emptyMap())
        if (message["method"] == null || params !is JsonObject) return message
        val meta = (params["_meta"] as? JsonObject).orEmpty() - stamp.keys + stamp
        return JsonObject(message + ("params" to JsonObject(params + ("_meta" to JsonObject(meta)))))
    }

    override fun close() {
        notices.tryEmit(JsonNull)
        connection.close()
        threads.shutdownNow()
    }

    private companion object {
        const val Concurrency = 8
    }
}

@Serializable
data class McpSessionRecord(val id: String, val scope: String, val stamp: JsonObject, val seen: Long)

interface McpSessionTable {
    fun read(): List<McpSessionRecord>
    fun write(records: List<McpSessionRecord>)
}

class McpSessionFile(private val path: Path) : McpSessionTable {
    override fun read(): List<McpSessionRecord> =
        runCatching { Json.decodeFromString<List<McpSessionRecord>>(Files.readString(path)) }.getOrDefault(emptyList())

    @Synchronized override fun write(records: List<McpSessionRecord>) {
        Files.createDirectories(path.parent)
        val staged = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(staged, Json.encodeToString(records))
            Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(staged) }
    }
}

class McpStreamableHttp(
    private val table: McpSessionTable? = null,
    private val retainMillis: Long = RetainMillis,
    private val scopes: (String) -> McpHttpScope?,
) : Service("mcp.http"), AutoCloseable {
    private val sessions = ConcurrentHashMap<String, McpHttpSession>()
    private val retained = ConcurrentHashMap<String, McpSessionRecord>().apply { table?.read()?.forEach { put(it.id, it) } }

    val message by post<McpPost, McpReply>("/mcp/{scope}")
    val events by stream<McpListen, JsonElement>(Route(HttpMethod.GET, "/mcp/{scope}"))
    val end by delete<McpEnd, McpReply>("/mcp/{scope}")

    init {
        message.handle(::receive)
        events.handle(::listen)
        end.handle(::finish)
    }

    fun sessions(scope: String): List<McpHttpSession> = sessions.values.filter { it.scope == scope }

    fun release(scope: String) {
        sessions(scope).forEach { close(it, forget = false) }
        persist()
    }

    fun expire(idleMillis: Long, now: Long = System.currentTimeMillis()) {
        sessions.values.filter { it.idle && now - it.seen > idleMillis }.forEach { close(it, forget = true) }
        retained.values.removeIf { !sessions.containsKey(it.id) && now - it.seen > minOf(idleMillis, retainMillis) }
        persist()
    }

    override fun close() {
        persist()
        sessions.values.toList().forEach { close(it, forget = false) }
    }

    private suspend fun receive(request: McpPost): McpReply {
        val arrival = arrival(request)
        refusal(arrival)?.let { (status, reason) -> return refused(status, reason) }
        val known = arrival.header(SessionHeader)
        val session = when {
            known != null -> sessions[known]?.takeIf { it.scope == arrival.scope } ?: resume(known, arrival)
                ?: return refused(404, "Session $known is not open; initialize again")
            request.method != "initialize" -> return refused(400, "$SessionHeader is required after initialize")
            else -> {
                val scope = scopes(arrival.scope) ?: return refused(404, "No MCP server is mounted at /mcp/${arrival.scope}")
                open(arrival, scope) ?: return refused(403, "/mcp/${arrival.scope} does not admit this caller")
            }
        }
        val answer = session.exchange(request.message(), arrival.headers)
        return McpReply(answer, mapOf(SessionHeader to session.id), if (answer == null) StatusCode.ACCEPTED else StatusCode.OK)
    }

    private fun listen(request: McpListen): Flow<JsonElement> {
        val arrival = arrival(request)
        refusal(arrival)?.let { (status, reason) -> throw Rejected(status, reason) }
        val known = arrival.header(SessionHeader) ?: throw Rejected(400, "$SessionHeader is required")
        return (sessions[known]?.takeIf { it.scope == arrival.scope } ?: resumeMounted(known, arrival)
            ?: throw Rejected(404, "Session $known is not open")).notices()
    }

    private fun finish(request: McpEnd): McpReply {
        val arrival = arrival(request)
        refusal(arrival)?.let { (status, reason) -> return refused(status, reason) }
        val known = arrival.header(SessionHeader) ?: return refused(400, "$SessionHeader is required")
        val session = sessions[known]?.takeIf { it.scope == arrival.scope }
        when {
            session != null -> close(session, forget = true)
            retained[known]?.scope == arrival.scope -> { retained.remove(known); persist() }
            else -> return refused(404, "Session $known is not open")
        }
        return McpReply(null, status = StatusCode.NO_CONTENT)
    }

    private fun open(arrival: McpHttpArrival, scope: McpHttpScope): McpHttpSession? {
        val stamp = scope.admit(arrival) ?: return null
        if (sessions.size >= Capacity) sessions.values.filter { it.idle }.minByOrNull { it.seen }?.let { close(it, forget = true) }
        val id = UUID.randomUUID().toString()
        return McpHttpSession(id, arrival.scope, JsonObject(stamp + (SessionKey to JsonPrimitive(id))), scope.server).also { session ->
            sessions[id] = session
            retained[id] = McpSessionRecord(id, session.scope, session.stamp, session.seen)
            scope.opened(session)
            persist()
        }
    }

    private suspend fun resume(id: String, arrival: McpHttpArrival): McpHttpSession? {
        val record = retainedFor(id, arrival) ?: return null
        val scope = withTimeoutOrNull(MountWaitMillis) {
            var mounted = scopes(record.scope)
            while (mounted == null) { delay(250); mounted = scopes(record.scope) }
            mounted
        } ?: return null
        return reopen(record, scope, arrival)
    }

    private fun resumeMounted(id: String, arrival: McpHttpArrival): McpHttpSession? {
        val record = retainedFor(id, arrival) ?: return null
        return reopen(record, scopes(record.scope) ?: return null, arrival)
    }

    private fun retainedFor(id: String, arrival: McpHttpArrival): McpSessionRecord? =
        retained[id]?.takeIf { it.scope == arrival.scope && System.currentTimeMillis() - it.seen <= retainMillis }

    private fun reopen(record: McpSessionRecord, scope: McpHttpScope, arrival: McpHttpArrival): McpHttpSession? = synchronized(retained) {
        sessions[record.id]?.let { return it }
        scope.admit(arrival) ?: return null
        McpHttpSession(record.id, record.scope, record.stamp, scope.server).also { session ->
            sessions[record.id] = session
            scope.opened(session)
        }
    }

    private fun close(session: McpHttpSession, forget: Boolean) {
        if (!sessions.remove(session.id, session)) return
        if (forget) retained.remove(session.id) else retained.computeIfPresent(session.id) { _, record -> record.copy(seen = session.seen) }
        try { scopes(session.scope)?.closed?.invoke(session) } finally {
            session.close()
            if (forget) persist()
        }
    }

    private fun persist() {
        val store = table ?: return
        sessions.values.forEach { session -> retained.computeIfPresent(session.id) { _, record -> record.copy(seen = session.seen) } }
        runCatching { store.write(retained.values.sortedBy { it.seen }) }
            .onFailure { System.err.println("MCP sessions were not saved: ${it.message}") }
    }

    private fun arrival(request: Request) = McpHttpArrival(request.pathParams.getValue("scope"), request.headers, request.queryParams)

    private fun refusal(arrival: McpHttpArrival): Pair<Int, String>? {
        val host = arrival.header("Host")
        if (host == null || !loopback("http://$host")) return 403 to "Invalid Host header"
        val origin = arrival.header("Origin")
        if (origin != null && !loopback(origin)) return 403 to "Origin is not permitted"
        return null
    }

    private fun refused(status: Int, reason: String) = McpReply(buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", JsonNull)
        putJsonObject("error") { put("code", -32000); put("message", reason) }
    }, status = StatusCode.entries.first { it.code == status })

    private fun loopback(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() in setOf("127.0.0.1", "localhost", "::1")
    }.getOrDefault(false)

    companion object {
        const val SessionHeader = "Mcp-Session-Id"
        const val SessionKey = "reaktor/session"
        const val RetainMillis = 30 * 60 * 1000L
        private const val MountWaitMillis = 90_000L
        private const val Capacity = 512
    }
}
