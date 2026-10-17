package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class WebMessageEnvelope(
    val protocol: Int = 1,
    val id: String,
    val kind: String,
    val session: String,
    val epoch: Long,
    val operation: String = "",
    val contract: String = "",
    val version: Int = 1,
    val schema: String = "",
    val payload: JsonElement = JsonNull,
    val replyTo: String? = null,
    val traceId: String? = null,
    val error: String? = null,
)

data class WebGrant(
    val appId: String,
    val revision: String,
    val principal: String,
    val workspace: String,
    val environment: Environment,
    val operations: Set<String>,
) {
    init { require(principal.isNotBlank() && workspace.isNotBlank()) }
}

class WebEventBinding<T>(
    val contract: ServiceContract,
    val operation: String,
    val serializer: KSerializer<T>,
    val events: Flow<T>,
) {
    internal fun encoded(json: Json): Flow<JsonElement> = events.map { json.encodeToJsonElement(serializer, it) }
    internal val descriptor: OperationDescriptor get() = OperationDescriptor(
        contract.id, contract.version, operation, Interaction.RequestReply, ServiceExecutionPhase.SERVER,
        serializer.schemaRef(), serializer.schemaRef(), null,
    )
}

class WebBridge(
    graph: Graph,
    internal val service: Service,
    internal val grants: StateFlow<WebGrant?>,
    internal val authorize: suspend (WebGrant, OperationDescriptor) -> Boolean,
    events: List<WebEventBinding<*>> = emptyList(),
) : BasicNode(graph) {
    internal val eventBindings = events.associateBy { it.operation }
    private val connections = MutableStateFlow<List<WebBridgeConnection>?>(emptyList())
    init {
        require(service.contract.id.isNotBlank()) { "Bridge services need a named contract" }
        require(service.handlers.map { it.endpoint.operation }.distinct().size == service.handlers.size)
        require(events.map { it.operation }.distinct().size == events.size)
    }
    internal fun connect(id: String, epoch: Long, app: WebApp, scope: CoroutineScope, send: suspend (String) -> Unit): WebBridgeConnection {
        val connection = WebBridgeConnection(this, id, epoch, app, scope, send) { closed ->
            connections.getAndUpdate { it?.minus(closed) }
        }
        while (true) {
            val current = connections.value
            if (current == null) { connection.close(); error("Web bridge is closed") }
            if (connections.compareAndSet(current, current + connection)) {
                if (connection.isClosed) connections.getAndUpdate { it?.minus(connection) }
                return connection
            }
        }
    }
    override fun close() {
        connections.getAndUpdate { null }?.forEach { it.close() }
        super.close()
    }
}

internal class WebBridgeConnection(
    private val bridge: WebBridge,
    private val sessionId: String,
    private val epoch: Long,
    private val app: WebApp,
    private val parent: CoroutineScope,
    private val send: suspend (String) -> Unit,
    private val onClosed: (WebBridgeConnection) -> Unit,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val inbound = Channel<String>(32)
    private val outbound = Channel<String>(32)
    private val pending = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val seen = MutableStateFlow<Set<String>>(emptySet())
    private val closed = MutableStateFlow(false)
    private val grant = bridge.grants.value
    val isClosed: Boolean get() = closed.value

    init {
        scope.launch {
            try { for (body in outbound) send(body) } finally { close() }
        }
        scope.launch {
            for (body in inbound) {
                try { accept(json.decodeFromString(WebMessageEnvelope.serializer(), body)) }
                catch (error: CancellationException) { throw error }
                catch (_: Throwable) { close(); break }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.grants.collect { if (it != grant) close() }
        }
    }

    fun configuration(): JsonObject = buildJsonObject {
            put("protocol", 1)
            put("session", sessionId)
            put("epoch", epoch)
            put("operations", JsonArray(bridge.service.operations.map {
                json.encodeToJsonElement(OperationDescriptor.serializer(), it)
            }))
            put("events", JsonArray(bridge.eventBindings.values.map {
                buildJsonObject {
                    put("operation", it.operation)
                    put("contract", it.contract.id)
                    put("version", it.contract.version)
                    put("schema", it.descriptor.response.fingerprint)
                }
            }))
    }

    fun receive(body: String) {
        if (closed.value) return
        if (!boundedWebJson(body) || !inbound.trySend(body).isSuccess) close()
    }

    private suspend fun accept(message: WebMessageEnvelope) {
        if (closed.value || message.protocol != 1 || message.session != sessionId || message.epoch != epoch) return
        if (!message.id.matches(Regex("[a-zA-Z0-9_-]{1,96}"))) return
        while (true) {
            val ids = seen.value
            if (message.id in ids) { reply(message, error = "duplicate_request"); return }
            if (ids.size >= 1024) { close(); return }
            if (seen.compareAndSet(ids, ids + message.id)) break
        }
        if (message.kind == "cancel" || message.kind == "unsubscribe") {
            message.replyTo?.let { pending.getAndUpdate { jobs -> jobs - it }[it]?.cancel() }
            reply(message)
            return
        }
        if (message.kind !in setOf("invoke", "subscribe")) { reply(message, error = "invalid_kind"); return }
        val handler = bridge.service.handlers.firstOrNull { it.endpoint.operation == message.operation }
        val event = bridge.eventBindings[message.operation]
        val descriptor = if (message.kind == "invoke") handler?.descriptor(bridge.service.contract) else event?.descriptor
        if (descriptor == null) { reply(message, error = "unknown_operation"); return }
        val schema = if (message.kind == "invoke") descriptor.request.fingerprint else descriptor.response.fingerprint
        if (message.contract != descriptor.contract || message.version != descriptor.version || message.schema != schema) {
            reply(message, error = "schema_mismatch")
            return
        }
        val currentGrant = grant
        if (currentGrant == null || bridge.grants.value != currentGrant ||
            currentGrant.appId != app.id || currentGrant.revision != app.revision ||
            message.operation !in currentGrant.operations
        ) { reply(message, error = "denied"); return }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!bridge.authorize(currentGrant, descriptor) || bridge.grants.value != currentGrant || closed.value) {
                    reply(message, error = "denied")
                    return@launch
                }
                if (message.kind == "invoke") {
                    val response = withTimeout(15_000) { invoke(requireNotNull(handler), message.payload, currentGrant) }
                    reply(message, response)
                } else {
                    reply(message)
                    requireNotNull(event).encoded(json).collect { payload ->
                        if (bridge.grants.value != currentGrant || closed.value) throw CancellationException("Authority revoked")
                        emit(message.copy(kind = "event", id = message.id, replyTo = message.id, payload = payload))
                    }
                }
            } catch (_: CancellationException) {
                if (scope.isActive) reply(message, error = "cancelled")
            } catch (_: SerializationException) {
                reply(message, error = "invalid_payload")
            } catch (_: Throwable) {
                reply(message, error = "operation_failed")
            } finally { pending.update { it - message.id } }
        }
        while (true) {
            val jobs = pending.value
            if (jobs.size >= 32) { job.cancel(); reply(message, error = "too_many_requests"); return }
            if (pending.compareAndSet(jobs, jobs + (message.id to job))) break
        }
        job.start()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invoke(handler: RequestHandler<*, *>, payload: JsonElement, grant: WebGrant): JsonElement {
        val typed = handler as RequestHandler<Request, Response>
        val request = json.decodeFromJsonElement(typed.requestSerializer, payload)
        request.environment = grant.environment
        request.attributes["reaktor.web.grant"] = grant
        return json.encodeToJsonElement(typed.responseSerializer, typed(request))
    }
    private suspend fun reply(message: WebMessageEnvelope, payload: JsonElement = JsonNull, error: String? = null) =
        emit(message.copy(kind = "reply", replyTo = message.id, payload = payload, error = error))
    private suspend fun emit(message: WebMessageEnvelope) {
        if (closed.value) return
        val body = json.encodeToString(WebMessageEnvelope.serializer(), message)
        if (!boundedWebJson(body)) {
            if (message.kind == "event") { close(); return }
            outbound.send(json.encodeToString(WebMessageEnvelope.serializer(), message.copy(payload = JsonNull, error = "response_too_large")))
        } else outbound.send(body)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbound.close()
        outbound.close()
        lifetime.cancel()
        pending.value = emptyMap()
        parent.launch {
            runCatching { send(json.encodeToString(WebMessageEnvelope(id = "closed", kind = "closed", session = sessionId, epoch = epoch))) }
        }
        onClosed(this)
    }
}

internal fun boundedWebJson(body: String): Boolean {
    if (body.length > 65_536 || body.encodeToByteArray().size > 65_536) return false
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in body) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 64) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return depth == 0 && !quoted
}
