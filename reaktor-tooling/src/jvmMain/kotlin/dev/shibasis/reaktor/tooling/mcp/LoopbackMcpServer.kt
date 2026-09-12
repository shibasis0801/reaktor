package dev.shibasis.reaktor.tooling.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.mcp.REAKTOR_MCP_PROTOCOL_VERSION
import dev.shibasis.reaktor.mcp.ReaktorMcpReadServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

data class LoopbackHttpResponse(val body: JsonElement, val status: Int = 200)

/** Owns a bounded, loopback-only HTTP transport. Tool definitions and authority belong to the host. */
class LoopbackMcpServer private constructor(
    private val server: HttpServer,
    private val executor: java.util.concurrent.ExecutorService,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    fun port(): Int = server.address.port

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        fun start(
            port: Int,
            mcp: () -> ReaktorMcpReadServer,
            read: (path: String, query: Map<String, String>) -> LoopbackHttpResponse? = { _, _ -> null },
        ): LoopbackMcpServer {
            require(port in 0..65535) { "Invalid port" }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)
            val executor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "reaktor-mcp-http").apply { isDaemon = true }
            }
            val transport = LoopbackMcpServer(server, executor)
            try {
                server.executor = executor
                server.createContext("/") { exchange ->
                    exchange.use { request ->
                        try { request.respond(mcp, read) }
                        catch (_: Exception) { runCatching { request.send(500, error("Request failed")) } }
                    }
                }
                server.start()
                return transport
            } catch (failure: Throwable) {
                transport.close()
                throw failure
            }
        }

        private fun HttpExchange.respond(
            mcp: () -> ReaktorMcpReadServer,
            read: (String, Map<String, String>) -> LoopbackHttpResponse?,
        ) {
            responseHeaders.set("Cache-Control", "no-store")
            responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Mcp-Protocol-Version, Mcp-Session-Id")
            responseHeaders.set("Access-Control-Expose-Headers", "Mcp-Protocol-Version, Mcp-Session-Id")
            val host = requestHeaders["Host"]?.singleOrNull()
            val origin = requestHeaders["Origin"]?.singleOrNull()
            if (host == null || !loopbackUri("http://$host", authority = true)) {
                send(403, error("Invalid Host header")); return
            }
            if (requestHeaders.containsKey("Origin") && (origin == null || !loopbackUri(origin))) {
                send(403, error("Origin is not permitted")); return
            }
            if (origin != null) {
                responseHeaders.set("Access-Control-Allow-Origin", origin)
                responseHeaders.set("Vary", "Origin")
            }
            when {
                requestMethod == "OPTIONS" -> send(204, null)
                requestMethod == "POST" && requestURI.path == "/mcp" -> {
                    responseHeaders.set("MCP-Protocol-Version", REAKTOR_MCP_PROTOCOL_VERSION)
                    val versions = requestHeaders["MCP-Protocol-Version"]
                    if (versions != null && versions.singleOrNull() != REAKTOR_MCP_PROTOCOL_VERSION) {
                        send(400, error("Unsupported MCP protocol version")); return
                    }
                    val body = requestBody.readNBytes(1_048_577)
                    if (body.size > 1_048_576) { send(413, error("Request body is too large")); return }
                    val response = mcp().handle(body.decodeToString())
                    send(if (response == null) 202 else 200, response)
                }
                requestMethod == "GET" -> {
                    val query = requestURI.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
                    }
                    val response = read(requestURI.path, query)
                    send(response?.status ?: 404, response?.body ?: error("Not found"))
                }
                else -> send(404, error("Not found"))
            }
        }

        private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
        private fun error(message: String) = buildJsonObject { put("error", message) }
        private fun HttpExchange.send(status: Int, response: JsonElement?) {
            if (response == null) { sendResponseHeaders(status, -1); return }
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.write(bytes)
        }

        private fun loopbackUri(value: String, authority: Boolean = false): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") &&
                uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() in setOf("127.0.0.1", "localhost", "::1") &&
                uri.userInfo == null && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null &&
                uri.port in -1..65535 && (!authority || uri.scheme == "http")
        }.getOrDefault(false)
    }
}
