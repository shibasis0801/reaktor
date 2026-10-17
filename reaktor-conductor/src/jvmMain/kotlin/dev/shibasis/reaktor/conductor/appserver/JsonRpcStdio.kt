package dev.shibasis.reaktor.conductor.appserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Everything the peer can send us that is not an answer to something we asked. */
sealed interface JsonRpcInbound {
    val method: String
    val params: JsonObject

    data class Notification(override val method: String, override val params: JsonObject) : JsonRpcInbound

    /**
     * A request originated by the server — an approval, an elicitation, a question. It carries an
     * id, so silence is not a valid response: the peer waits.
     */
    data class ServerRequest(val id: JsonElement, override val method: String, override val params: JsonObject) : JsonRpcInbound
}

class JsonRpcException(val code: Int, message: String) : RuntimeException(message)

/**
 * Newline-delimited JSON-RPC over one process's stdio.
 *
 * Kept free of any Codex vocabulary so it can be exercised against a pair of pipes rather than a
 * subprocess, which is the only way to test reconnect, malformed lines and out-of-order answers
 * without depending on someone else's binary being installed.
 *
 * Verified against `codex app-server` 0.154.0: one JSON object per line, and responses come back
 * without the `jsonrpc` member, so nothing here requires it.
 */
class JsonRpcStdio(
    private val input: BufferedReader,
    private val output: Writer,
    scope: CoroutineScope,
    private val onLine: (String) -> Unit = {},
) : AutoCloseable {
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val writeLock = Any()

    private val messages = Channel<JsonRpcInbound>(256)
    val inbound = messages.receiveAsFlow()

    /** Completes when the peer closes its side, so a caller can tell EOF from a hung turn. */
    val closed = CompletableDeferred<Unit>()

    private val reader: Job = scope.launch {
        try {
            while (true) {
                val line = runCatching { input.readLine() }.getOrNull() ?: break
                onLine(line)
                val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val id = message["id"]
                val method = message["method"]?.jsonPrimitive?.contentOrNull
                when {
                    // Both an id and a method: the server is asking us something.
                    id != null && method != null ->
                        messages.send(JsonRpcInbound.ServerRequest(id, method, message.objectOrEmpty("params")))

                    method != null -> messages.send(JsonRpcInbound.Notification(method, message.objectOrEmpty("params")))

                    id != null -> {
                        val waiting = pending.remove(id.jsonPrimitive.longOrNull ?: continue) ?: continue
                        val error = message["error"]?.jsonObject
                        if (error != null) {
                            waiting.completeExceptionally(JsonRpcException(
                                error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                                error["message"]?.jsonPrimitive?.contentOrNull ?: "JSON-RPC error",
                            ))
                        } else {
                            waiting.complete(message.objectOrEmpty("result"))
                        }
                    }
                }
            }
        } finally {
            closed.complete(Unit)
            messages.close()
            // A peer that dies mid-request must fail every caller rather than leaving them parked.
            pending.values.forEach { it.completeExceptionally(JsonRpcException(-1, "Transport closed before a reply arrived")) }
            pending.clear()
        }
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val id = nextId.incrementAndGet()
        val waiting = CompletableDeferred<JsonObject>()
        check(!closed.isCompleted) { "Transport is closed" }
        pending[id] = waiting
        return try {
            write(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
            })
            withTimeout(60_000) { waiting.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); put("params", params)
    })

    /** Answers a [JsonRpcInbound.ServerRequest]. Its id is echoed exactly as it arrived. */
    fun respond(id: JsonElement, result: JsonObject) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    })

    fun reject(id: JsonElement, method: String) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", -32601); put("message", "Unsupported provider request: $method") }
    })

    private fun write(message: JsonObject) = synchronized(writeLock) {
        check(!closed.isCompleted) { "Transport is closed" }
        output.write(message.toString())
        output.write("\n")
        output.flush()
    }

    override fun close() {
        reader.cancel()
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

internal fun JsonObject.objectOrEmpty(key: String): JsonObject =
    this[key] as? JsonObject ?: JsonObject(emptyMap())
