package dev.shibasis.reaktor.mcp

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.*

class McpConnection internal constructor(
    private val exchange: (String, Map<String, String>) -> JsonElement?,
    private val dispose: () -> Unit,
) : McpMessageHandler {
    override fun handle(body: String): JsonElement? = exchange(body, emptyMap())
    fun handle(body: String, headers: Map<String, String>): JsonElement? = exchange(body, headers)
    fun close() = dispose()
}

internal class McpSubscriptionState {
    private val lock = SynchronizedObject()
    private val subscriptions = linkedMapOf<String, McpSubscription>()
    private val emitters = linkedSetOf<(JsonElement) -> Unit>()
    private var sequence = 0L
    private var closed = false

    fun requireOpen() = synchronized(lock) { check(!closed) { "MCP connection is closed" } }
    fun list(): List<McpSubscription> = synchronized(lock) { subscriptions.values.toList() }
    fun nextId(): String = synchronized(lock) {
        var id: String
        do { id = "sub-${++sequence}" } while (id in subscriptions)
        id
    }

    fun listen(subscription: McpSubscription) = synchronized(lock) {
        check(!closed) { "MCP connection is closed" }
        require(subscription.id in subscriptions || subscriptions.size < 128) { "Too many subscriptions on this connection" }
        subscriptions[subscription.id] = subscription
    }

    fun remove(id: String): Boolean = synchronized(lock) { subscriptions.remove(id) != null }

    fun attach(sink: (JsonElement) -> Unit): () -> Unit {
        synchronized(lock) {
            check(!closed) { "MCP connection is closed" }
            emitters += sink
        }
        return { synchronized(lock) { emitters -= sink }; Unit }
    }

    fun close() = synchronized(lock) {
        closed = true
        subscriptions.clear()
        emitters.clear()
    }

    fun publish(method: String, params: JsonObject, predicate: (McpSubscription) -> Boolean): List<JsonObject> {
        val matching = list().filter { runCatching { predicate(it) }.getOrDefault(false) }
        val metadata = params["_meta"] as? JsonObject ?: JsonObject(emptyMap())
        val values = params.filterKeys { it != "_meta" }
        return matching.mapNotNull { subscription ->
            val sinks = synchronized(lock) {
                if (closed || subscriptions[subscription.id] != subscription) null else emitters.toList()
            } ?: return@mapNotNull null
            val notification = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", JsonObject(values + ("_meta" to JsonObject(metadata +
                    (MCP_SUBSCRIPTION_ID to JsonPrimitive(subscription.id))))))
            }
            sinks.forEach { sink -> runCatching { sink(notification) }.onFailure { synchronized(lock) { emitters -= sink } } }
            notification
        }
    }
}

internal class McpNotifications {
    private val lock = SynchronizedObject()
    val local = McpSubscriptionState()
    private val connections = linkedSetOf(local)

    fun open(sink: (JsonElement) -> Unit, exchange: (String, Map<String, String>, McpSubscriptionState) -> JsonElement?): McpConnection {
        val state = McpSubscriptionState()
        state.attach(sink)
        synchronized(lock) { connections += state }
        return McpConnection({ body, headers -> state.requireOpen(); exchange(body, headers, state) }) {
            state.close()
            synchronized(lock) { connections -= state }
        }
    }

    fun subscriptions(): List<McpSubscription> = synchronized(lock) { connections.toList() }.flatMap { it.list() }

    fun publish(method: String, params: JsonObject, predicate: (McpSubscription) -> Boolean): List<JsonObject> =
        synchronized(lock) { connections.toList() }.flatMap { it.publish(method, params, predicate) }
}

