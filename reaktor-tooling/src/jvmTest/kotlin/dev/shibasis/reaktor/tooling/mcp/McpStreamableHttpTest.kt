package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.mcp.MCP_REQUEST_VERSION
import dev.shibasis.reaktor.mcp.MCP_TASKS_VERSION
import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.service.contract.Served
import dev.shibasis.reaktor.service.contract.listen
import dev.shibasis.reaktor.service.contract.toRouter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.nio.file.Files
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpStreamableHttpTest {
    private val seen = CopyOnWriteArrayList<JsonObject>()
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private val server = ReaktorMcpServer("workspace", "1", "", listOf(
        McpTool("whoami", "Echoes who called", buildJsonObject { put("type", "object") }, readOnly = true, idempotent = true,
            executeWithMeta = { _, metadata -> seen += metadata; JsonPrimitive("ok") }) { JsonPrimitive("ok") }))
    private val bestbuds: (String) -> McpHttpScope? = { scope ->
        if (scope != "bestbuds") null else McpHttpScope(server,
            admit = { arrival -> if (arrival.query["seat"] == "blocked") null else buildJsonObject { put("reaktor/caller", arrival.header("Reaktor-Principal") ?: "anonymous") } },
            opened = { opened += it.id }, closed = { closed += it.id })
    }
    private val transport = McpStreamableHttp(scopes = bestbuds)
    private val listening = Served(transport).toRouter().listen("127.0.0.1", 0)
    private val http = HttpClient.newHttpClient()
    private val base get() = "http://127.0.0.1:${listening.port}/mcp/bestbuds"

    @AfterTest fun stop() {
        transport.close()
        listening.close()
    }

    @Test fun eachSessionStampsItsOwnCallerOntoEveryCall() {
        val one = initialize("claude:one")
        val two = initialize("claude:two")
        assertEquals(202, post(rpc("notifications/initialized", id = null), one).statusCode())
        for (session in listOf(one, two)) {
            val called = post(rpc("tools/call", params = buildJsonObject {
                put("name", "whoami"); putJsonObject("arguments") {}; putJsonObject("_meta") { put("reaktor/caller", "forged") }
            }), session)
            assertEquals(200, called.statusCode(), called.body())
        }
        assertEquals(listOf("claude:one", "claude:two"), seen.map { it.getValue("reaktor/caller").jsonPrimitive.content })
        assertEquals(listOf(one, two), seen.map { it.getValue(McpStreamableHttp.SessionKey).jsonPrimitive.content })
        assertEquals(listOf(one, two), opened)
    }

    @Test fun callsOutsideAnOpenSessionAreRefusedAsTheSpecificationSays() {
        assertEquals(400, post(rpc("tools/list")).statusCode())
        assertEquals(404, post(rpc("tools/list"), "unknown").statusCode())
        assertEquals(404, post(initialization(), path = "http://127.0.0.1:${listening.port}/mcp/elsewhere").statusCode())
        assertEquals(403, post(initialization(), path = "$base?seat=blocked").statusCode())
        assertEquals(403, post(initialization(), headers = mapOf("Origin" to "https://example.com")).statusCode())
        assertTrue(opened.isEmpty())
    }

    @Test fun endingASessionClosesItAndLaterCallsMustInitializeAgain() {
        val session = initialize()
        val ended = http.send(HttpRequest.newBuilder(URI(base)).header("Mcp-Session-Id", session).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(204, ended.statusCode())
        assertEquals("", ended.body())
        assertEquals(listOf(session), closed)
        assertEquals(404, post(rpc("tools/list"), session).statusCode())
    }

    @Test fun theEventStreamCarriesNotificationsForItsSession() {
        val session = initialize()
        val listened = post(rpc("subscriptions/listen", params = buildJsonObject {
            putJsonArray("resourceSubscriptions") { add("plan://one") }
            putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) }
        }), session)
        assertEquals(200, listened.statusCode(), listened.body())
        val stream = http.sendAsync(events(session), HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS)
        assertEquals(200, stream.statusCode())
        assertEquals("text/event-stream", stream.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
        val data = CompletableFuture.supplyAsync { stream.body().filter { it.startsWith("data:") }.findFirst().orElse("") }
        val deadline = System.currentTimeMillis() + 10_000
        while (transport.sessions("bestbuds").none { it.listening } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        server.notifyResourceUpdated("plan://one")
        assertContains(data.get(10, TimeUnit.SECONDS), "notifications/resources/updated")
        assertEquals(404, http.send(events("unknown"), HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    @Test fun endingASessionEndsItsEventStream() {
        val session = initialize()
        val stream = http.sendAsync(events(session), HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS)
        val drained = CompletableFuture.supplyAsync { stream.body().count() }
        val deadline = System.currentTimeMillis() + 10_000
        while (transport.sessions("bestbuds").none { it.listening } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val ended = http.send(HttpRequest.newBuilder(URI(base)).header("Mcp-Session-Id", session).DELETE().build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(204, ended.statusCode())
        assertTrue(drained.get(10, TimeUnit.SECONDS) >= 1)
    }

    @Test fun idleSessionsExpireWhileListenersStay() {
        val idle = initialize()
        val listened = initialize("claude:two")
        val stream = http.sendAsync(events(listened), HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS)
        assertEquals(200, stream.statusCode())
        val deadline = System.currentTimeMillis() + 10_000
        while (transport.sessions("bestbuds").none { it.listening } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        transport.expire(0, System.currentTimeMillis() + 1000)
        assertEquals(listOf(idle), closed)
        assertEquals(404, post(rpc("tools/list"), idle).statusCode())
        assertEquals(200, post(rpc("tools/list"), listened).statusCode())
        stream.body().close()
    }

    @Test fun aRestartedTransportResumesTheSessionsItsTableKept() {
        val table = McpSessionFile(Files.createTempDirectory("mcp-sessions").resolve("sessions.json"))
        val whoami = rpc("tools/call", params = buildJsonObject { put("name", "whoami"); putJsonObject("arguments") {} })
        val session = served(McpStreamableHttp(table, scopes = bestbuds)) { path ->
            val response = post(initialization(), path = path, headers = mapOf("Reaktor-Principal" to "claude:kept"))
            response.headers().firstValue("Mcp-Session-Id").orElseThrow()
        }
        served(McpStreamableHttp(table, scopes = bestbuds)) { path ->
            assertEquals(200, post(whoami, session, path).statusCode())
            assertEquals("claude:kept", seen.last().getValue("reaktor/caller").jsonPrimitive.content)
            assertEquals(session, seen.last().getValue(McpStreamableHttp.SessionKey).jsonPrimitive.content)
        }
        served(McpStreamableHttp(table, retainMillis = -1, scopes = bestbuds)) { path -> assertEquals(404, post(whoami, session, path).statusCode()) }
        served(McpStreamableHttp(table, scopes = bestbuds)) { path ->
            assertEquals(204, http.send(HttpRequest.newBuilder(URI(path)).header("Mcp-Session-Id", session).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode())
        }
        served(McpStreamableHttp(table, scopes = bestbuds)) { path -> assertEquals(404, post(whoami, session, path).statusCode()) }
    }

    private fun <T> served(transport: McpStreamableHttp, block: (String) -> T): T {
        val listening = Served(transport).toRouter().listen("127.0.0.1", 0)
        try { return block("http://127.0.0.1:${listening.port}/mcp/bestbuds") } finally { try { transport.close() } finally { listening.close() } }
    }

    private fun initialize(principal: String = "claude:one"): String {
        val response = post(initialization(), headers = mapOf("Reaktor-Principal" to principal))
        assertEquals(200, response.statusCode(), response.body())
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow()
    }

    private fun initialization() = rpc("initialize", params = buildJsonObject {
        put("protocolVersion", "2025-06-18")
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") { put("name", "test"); put("version", "1") }
    })

    private fun rpc(method: String, id: Int? = 1, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        put("method", method)
        put("params", params)
    }

    private fun events(session: String) = HttpRequest.newBuilder(URI(base)).header("Accept", "text/event-stream").header("Mcp-Session-Id", session).GET().build()

    private fun post(body: JsonObject, session: String? = null, path: String = base, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(path)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        session?.let { request.header("Mcp-Session-Id", it) }
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
