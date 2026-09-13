package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.mcp.REAKTOR_MCP_PROTOCOL_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Read-only kernel bridge. A listening local port does not establish workspace identity. */
internal class WorkspaceGraphBridge(private val root: File, url: String) : AutoCloseable {
    private val endpoint = URI(url).also {
        require(it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1") && it.path == "/mcp") {
            "The graph bridge requires a local kernel /mcp endpoint"
        }
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    suspend fun exchange(message: String): JsonElement? = withContext(Dispatchers.IO) {
        val snapshot = send(HttpRequest.newBuilder(endpoint.resolve("/workspace/identity")).GET()).jsonObject
        require(snapshot["workspaceRoot"]?.jsonPrimitive?.contentOrNull == root.canonicalPath) {
            "The kernel at this endpoint belongs to another workspace; start the kernel for ${root.canonicalPath}"
        }
        send(HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", REAKTOR_MCP_PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(message))).takeUnless { it == JsonNull }
    }
    private fun send(builder: HttpRequest.Builder): JsonElement {
        val response = http.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { it.readNBytes(2_000_001) }
        check(bytes.size <= 2_000_000) { "Graph response exceeded the transport budget" }
        if (response.statusCode() == 202 && bytes.isEmpty()) return JsonNull
        check(response.statusCode() == 200) { "Kernel unavailable (HTTP ${response.statusCode()})" }
        return ConductorJson.parseToJsonElement(bytes.decodeToString())
    }
    override fun close() { http.shutdownNow() }
}
