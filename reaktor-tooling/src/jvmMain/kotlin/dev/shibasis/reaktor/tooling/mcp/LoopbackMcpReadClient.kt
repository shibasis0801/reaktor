package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.mcp.*

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

data class McpInspection(val server: JsonObject, val tools: List<JsonObject>, val resources: List<JsonObject>)

/** Inspector for a local Reaktor read server. The host supplies its bound port; no remote URL or credentials are accepted. */
class LoopbackMcpReadClient(private val port: Int) {
    init { require(port in 1..65535) }
    private val ids = AtomicLong()
    val endpoint: String get() = "http://127.0.0.1:$port/mcp"

    fun inspect(): McpInspection {
        val server = rpc("initialize", buildJsonObject {
            put("protocolVersion", REAKTOR_MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "reaktor-desktop-inspector"); put("version", "1") })
        })
        require(server["protocolVersion"]?.jsonPrimitive?.content == REAKTOR_MCP_PROTOCOL_VERSION) { "Unsupported MCP protocol" }
        return McpInspection(server, list("tools"), list("resources"))
    }

    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val tool = list("tools").firstOrNull { it["name"]?.jsonPrimitive?.content == name }
            ?: error("Tool is no longer advertised; refresh the connection")
        require(tool["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true) {
            "This inspector only invokes advertised read tools"
        }
        return rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    fun readResource(uri: String): JsonObject {
        require(list("resources").any { it["uri"]?.jsonPrimitive?.content == uri }) { "Resource is no longer advertised" }
        return rpc("resources/read", buildJsonObject { put("uri", uri) })
    }

    private fun list(kind: String): List<JsonObject> = buildList {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = rpc("$kind/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            addAll(page[kind]?.jsonArray.orEmpty().map { it.jsonObject })
            require(size <= 2000) { "MCP catalog exceeds the inspector limit" }
            cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
            require(cursor == null || seen.add(cursor!!)) { "MCP server repeated a pagination cursor" }
        } while (cursor != null)
    }

    private fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
        val connection = URI(endpoint).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("MCP-Protocol-Version", REAKTOR_MCP_PROTOCOL_VERSION)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "MCP returned HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readNBytes(4_000_001) }
            require(bytes.size <= 4_000_000) { "MCP response exceeds 4 MB; narrow the tool arguments" }
            val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            check(response["id"]?.jsonPrimitive?.longOrNull == id) { "MCP response id did not match the request" }
            response["error"]?.jsonObject?.let { error(it["message"]?.jsonPrimitive?.content ?: "MCP request failed") }
            return requireNotNull(response["result"] as? JsonObject) { "MCP response has no result" }
        } finally { connection.disconnect() }
    }
}
