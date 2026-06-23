package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.Request
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Thin Memgraph access over the reaktorServer graph gateway, reached through an
 * `APP_SERVER_HTTP` Workers VPC service binding.
 *
 * A Worker cannot speak Bolt to the private Memgraph: raw `cloudflare:sockets`
 * connections to private IPs are blocked, and Workers VPC bindings are HTTP-only.
 * reaktorServer owns the pooled JVM Bolt driver and exposes a small HTTP surface
 * (the `/_graph` routes); this client calls that gateway. Keep graph traffic on this thin
 * adapter — use the full business endpoints only when an operation genuinely
 * needs centralized domain invariants.
 *
 * The `http://app-server` origin is a placeholder; the VPC service binding routes
 * to its configured private origin regardless of host.
 */
class GraphGateway internal constructor(
    private val service: WorkerService,
    private val authorization: suspend () -> String?,
    private val basePath: String,
) {
    private suspend fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        authorization()?.let { put(AUTHORIZATION_HEADER, it) }
        putAll(extra)
    }

    /** Gateway liveness (`GET {basePath}/health`). */
    suspend fun health(): Boolean =
        service.fetch(url = origin("/health"), method = "GET", headers = headers()).ok

    /** Read-only Cypher. Returns the gateway JSON envelope `{ records, summary }`. */
    suspend fun read(cypher: String, params: JsonObject = EMPTY_PARAMS): JsonElement =
        sendCypher("/read", cypher, params)

    /**
     * Run a server-registered named query by id — the production-safe path that
     * keeps raw Cypher out of Workers. The query text lives in reaktorServer's
     * registry; the Worker passes only the id and params.
     */
    suspend fun queryId(id: String, params: JsonObject = EMPTY_PARAMS): JsonElement {
        val body = buildJsonObject {
            put("id", id)
            put("params", params)
        }
        return send("/query-id", body.toString(), headers(JSON_BODY))
    }

    private suspend fun sendCypher(
        path: String,
        cypher: String,
        params: JsonObject,
    ): JsonElement {
        val body = buildJsonObject {
            put("query", cypher)
            put("params", params)
        }
        return send(path, body.toString(), headers(JSON_BODY))
    }

    private suspend fun send(path: String, body: String, requestHeaders: Map<String, String>): JsonElement {
        val response = service.fetch(url = origin(path), method = "POST", body = body, headers = requestHeaders)
        val text = response.text()
        check(response.ok) { "Graph gateway HTTP ${response.status}: ${text.take(ERROR_PREVIEW)}" }
        return json.parseToJsonElement(text)
    }

    private fun origin(path: String): String = "http://app-server$basePath$path"

    private companion object {
        const val ERROR_PREVIEW = 500
        val EMPTY_PARAMS = JsonObject(emptyMap())
        val JSON_BODY = mapOf("Content-Type" to "application/json; charset=utf-8")
    }
}

/**
 * Declarative graph-gateway binding: the `APP_SERVER_HTTP` VPC service name, the
 * service-account auth binding, and the gateway base path on reaktorServer.
 */
class GraphBinding internal constructor(
    internal val serviceName: String,
    internal val auth: ServiceAccountAuthBinding?,
    internal val basePath: String,
)

fun graph(
    name: String,
    auth: ServiceAccountAuthBinding? = null,
    basePath: String = "/_graph",
): GraphBinding = GraphBinding(name, auth, basePath)

fun CloudflareContext.graph(binding: GraphBinding): GraphGateway =
    GraphGateway(
        service = requireService(binding.serviceName),
        authorization = { binding.auth?.let { serviceAccountBearer(it) } },
        basePath = binding.basePath,
    )

suspend inline fun <T> CloudflareContext.graph(
    binding: GraphBinding,
    block: suspend GraphGateway.() -> T,
): T = graph(binding).block()

suspend inline fun <T> CloudflareRequest.graph(
    binding: GraphBinding,
    block: suspend GraphGateway.() -> T,
): T = context.graph(binding, block)

suspend inline fun <T> Request.graph(
    binding: GraphBinding,
    block: suspend GraphGateway.() -> T,
): T = context.graph(binding, block)
