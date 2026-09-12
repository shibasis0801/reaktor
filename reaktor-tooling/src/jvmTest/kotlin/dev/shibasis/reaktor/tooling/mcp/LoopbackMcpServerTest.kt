package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.mcp.*
import java.net.Socket
import kotlinx.serialization.json.*
import kotlin.test.*

class LoopbackMcpServerTest {
    @Test fun clientNegotiatesAndCallsTheReadOnlyRegistry() {
        LoopbackMcpServer.start(0, { registry() }).use { server ->
            val client = LoopbackMcpReadClient(server.port())
            val inspection = client.inspect()
            assertEquals("tooling-test", inspection.server["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(listOf("echo"), inspection.tools.map { it["name"]?.jsonPrimitive?.content })
            assertFalse(client.callTool("echo", buildJsonObject { put("value", "hello") }).toString().isBlank())
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

    private fun registry() = ReaktorMcpReadServer("tooling-test", "1", "Read only", listOf(
        McpReadTool("echo", "Read supplied arguments", objectSchema(mapOf("value" to stringSchema("Value")))) { it },
    ))
}
