package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.mcp.*
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*

class LoopbackMcpServerTest {
    @Test fun browserPairingCannotReachTheOwnerAndCanBeRevoked() {
        val ownerToken = "owner-".repeat(8)
        val browserToken = "browser-".repeat(8)
        var pairing: Pair<String, String>? = "https://manna.ac" to browserToken
        val browser = BrowserMcpEndpoint({ pairing }, ReaktorMcpServer("inference", "1", "", listOf(
            McpTool("inference.ask", "Question", emptyObjectSchema(), readOnly = false, idempotent = false) { buildJsonObject { put("reply", "answer") } },
        )))
        val client = HttpClient.newHttpClient()
        LoopbackMcpServer.start(0, { registry() }, ownerToken, post = { _, _ -> error("Browser must never reach owner operations") }, browser = browser).use { server ->
            fun send(path: String, origin: String?, token: String?, method: String = "POST"): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port()}$path"))
                    .header("Access-Control-Request-Private-Network", "true")
                origin?.let { request.header("Origin", it) }; token?.let { request.header("Authorization", "Bearer $it") }
                return client.send(request.method(method, if (method == "OPTIONS") HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")).build(), HttpResponse.BodyHandlers.ofString())
            }
            val preflight = send("/browser/mcp", "https://manna.ac", null, "OPTIONS")
            assertEquals(204, preflight.statusCode())
            assertEquals("https://manna.ac", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
            assertEquals("true", preflight.headers().firstValue("Access-Control-Allow-Private-Network").orElse(null))
            assertEquals(401, send("/browser/mcp", "https://manna.ac", null).statusCode())
            assertEquals(401, send("/browser/mcp", "https://manna.ac", ownerToken).statusCode())
            for (origin in listOf(null, "https://evil.manna.ac", "https://attacker.example", "null"))
                assertEquals(403, send("/browser/mcp", origin, browserToken).statusCode())
            for (path in listOf("/mcp", "/operations", "/run"))
                assertEquals(403, send(path, "https://manna.ac", browserToken).statusCode())
            assertEquals(401, send("/mcp", null, browserToken).statusCode())
            val tools = Json.parseToJsonElement(send("/browser/mcp", "https://manna.ac", browserToken).body()).jsonObject
                .getValue("result").jsonObject.getValue("tools").jsonArray
            assertEquals(listOf("inference.ask"), tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })
            pairing = null
            assertEquals(403, send("/browser/mcp", "https://manna.ac", browserToken).statusCode())
        }
    }

    @Test fun clientNegotiatesAndCallsTheReadOnlyRegistry() {
        LoopbackMcpServer.start(0, { registry() }).use { server ->
            val client = LoopbackMcpReadClient(server.port())
            val inspection = client.inspect()
            assertEquals("tooling-test", inspection.server["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(listOf("echo"), inspection.tools.map { it["name"]?.jsonPrimitive?.content })
            assertFalse(client.callTool("echo", buildJsonObject { put("value", "hello") }).toString().isBlank())
        }
    }

    @Test fun runReadsRemainAvailableWhileOperationsWait() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val token = "test-token-".repeat(4)
        val client = HttpClient.newHttpClient()
        LoopbackMcpServer.start(0, { registry() }, bearerToken = token, post = { _, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            LoopbackHttpResponse(buildJsonObject { put("completed", true) })
        }, read = { _, _ -> LoopbackHttpResponse(buildJsonObject { put("state", "Running") }) }).use { server ->
            fun request(path: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port()}$path"))
                .timeout(Duration.ofSeconds(3))
            val operations = (1..4).map {
                client.sendAsync(request("/operations").header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertEquals(401, client.send(request("/run").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                val response = client.send(request("/run").header("Authorization", "Bearer $token").GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertEquals("Running", Json.parseToJsonElement(response.body()).jsonObject["state"]?.jsonPrimitive?.content)
            } finally {
                release.countDown()
            }
            operations.forEach { assertEquals(200, it.get(3, TimeUnit.SECONDS).statusCode()) }
        }
    }

    @Test fun rejectsRemoteHostOriginUnsupportedProtocolAndOversizedBodies() {
        LoopbackMcpServer.start(0, { registry() }).use { server ->
            fun request(headers: String, body: String = "{}"): String = Socket("127.0.0.1", server.port()).use { socket ->
                socket.soTimeout = 5000
                val payload = "POST /mcp HTTP/1.1\r\n$headers\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().write(payload.toByteArray())
                socket.getInputStream().bufferedReader().readLine()
            }
            assertTrue(request("Host: attacker.example").contains("403"))
            assertTrue(request("Host: localhost\r\nOrigin: https://attacker.example").contains("403"))
            assertTrue(request("Host: localhost\r\nMCP-Protocol-Version: unsupported").contains("400"))
            assertTrue(request("Host: localhost", "x".repeat(1_048_577)).contains("413"))
        }
    }

    @Test fun closeReleasesTheBoundPortAndIsIdempotent() {
        val server = LoopbackMcpServer.start(0, { registry() })
        val port = server.port()
        server.close(); server.close()
        LoopbackMcpServer.start(port, { registry() }).close()
    }

    private fun registry() = ReaktorMcpServer("tooling-test", "1", "Read only", listOf(
        McpTool("echo", "Read supplied arguments", objectSchema(mapOf("value" to stringSchema("Value"))), readOnly = true, idempotent = true) { it },
    ))
}
