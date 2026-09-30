package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.ProviderAvailability
import dev.shibasis.reaktor.tooling.ToolingProviderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * A provider reached over HTTP: a loopback endpoint of our own, or a vendor's hosted server.
 *
 * A server may answer a POST with one JSON body or with an event stream that carries the answer
 * among other messages; both are read here. A session id the server hands out is kept and sent
 * back, and when the server forgets it the handshake is simply repeated.
 */
class HttpMcpLink(
    private val provider: String,
    /** Asked before every request, so an endpoint that moves ports or comes back later is found again. */
    private val endpoint: () -> URI?,
    /** Asked before every request and never stored, so a rotated credential is picked up and none is held here. */
    private val headers: () -> Map<String, String> = { emptyMap() },
    override val cheap: Boolean = false,
    private val protocolVersion: String = DOOR_LEGACY_PROTOCOL,
    private val timeout: Duration = Duration.ofSeconds(120),
    private val whenMissing: String = "no endpoint is answering",
    /** Called when the server refuses the credential, so a cached one is fetched again next time. */
    private val onRejected: () -> Unit = {},
    private val signIn: String? = null,
) : McpLink {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val generations = AtomicLong()
    @Volatile private var session: String? = null
    @Volatile private var last = ToolingProviderState(provider, ProviderAvailability.Unknown)

    override val generation: Long get() = generations.get()

    override fun state(): ToolingProviderState = last

    override suspend fun exchange(request: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val target = endpoint() ?: error(whenMissing)
            val builder = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
            headers().forEach { (name, value) -> builder.header(name, value) }
            session?.let { builder.header("Mcp-Session-Id", it) }
            val response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(MAX_BYTES + 1) }
            check(bytes.size <= MAX_BYTES) { "the response is over ${MAX_BYTES / 1_000_000}MB" }
            val status = response.statusCode()
            if (status == 404 && session != null) { session = null; generations.incrementAndGet(); error("the server forgot this session; the next call starts a new one") }
            if (status == 401 || status == 403) {
                // Some servers answer a refused call with a proper message and an error status: the sign-in is fine,
                // the caller lacks a permission, and the server says which. That sentence is worth more than ours.
                val said = runCatching { DoorJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
                if (status == 403 && said != null && (said["result"] != null || said["error"] != null)) return@withContext said.also { available() }
                onRejected(); throw CredentialMissing(signIn ?: "sign-in required (HTTP $status)")
            }
            if (status == 202 || bytes.isEmpty()) return@withContext null.also { available() }
            check(status == 200) { "HTTP $status" }
            response.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
            val body = bytes.decodeToString()
            val answer = if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) fromEvents(body, request["id"]) else DoorJson.parseToJsonElement(body) as? JsonObject
            answer.also { available() }
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.simpleName.orEmpty()
            unavailable(if (failure is CredentialMissing) ProviderAvailability.AuthRequired else ProviderAvailability.Unavailable, reason)
            throw IllegalStateException("$provider: $reason")
        }
    }

    override suspend fun close() { http.shutdownNow() }

    /** The stream may carry notifications and requests of the server's own; the answer is the message with our id. */
    private fun fromEvents(body: String, id: kotlinx.serialization.json.JsonElement?): JsonObject? {
        val wanted = (id as? JsonPrimitive)?.content
        val data = StringBuilder()
        fun flush(): JsonObject? {
            val message = data.toString().takeIf(String::isNotBlank)?.let { runCatching { DoorJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            data.clear()
            return message?.takeIf { it["method"] == null && (it["id"]?.takeUnless { value -> value is JsonNull } as? JsonPrimitive)?.contentOrNull == wanted }
        }
        body.lineSequence().forEach { line ->
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isBlank() -> flush()?.let { return it }
            }
        }
        return flush()
    }

    private fun available() { last = ToolingProviderState(provider, ProviderAvailability.Available, null, System.currentTimeMillis()) }
    private fun unavailable(availability: ProviderAvailability, detail: String) { last = ToolingProviderState(provider, availability, detail, System.currentTimeMillis()) }

    private companion object { const val MAX_BYTES = 32_000_000 }
}
