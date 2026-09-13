package dev.shibasis.reaktor.conductor.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkspaceGraphBridgeTest {
    @Test fun discoveryRefreshesTheKernelPortWithoutChangingTheInstalledBridge() = runBlocking {
        val root = Files.createTempDirectory("graph-discovery").toFile()
        val data = dev.shibasis.reaktor.conductor.workspace.AgentWorkspaceConnection.defaultDirectory(root)
        Files.createDirectories(data)
        val calls = AtomicInteger()
        fun server(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/workspace/identity") { exchange ->
                val bytes = kotlinx.serialization.json.buildJsonObject { put("workspaceRoot", kotlinx.serialization.json.JsonPrimitive(root.canonicalPath)) }.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/mcp") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(202, -1); exchange.close() }
            start()
        }
        val first = server(); val next = server()
        try {
            WorkspaceGraphBridge(root).use { bridge ->
                for (server in listOf(first, next)) {
                    Files.writeString(data.resolve("graph-connection.json"), kotlinx.serialization.json.buildJsonObject {
                        put("workspaceRoot", kotlinx.serialization.json.JsonPrimitive(root.canonicalPath))
                        put("url", kotlinx.serialization.json.JsonPrimitive("http://127.0.0.1:${server.address.port}/mcp"))
                    }.toString())
                    assertNull(bridge.exchange("{}"))
                }
                assertEquals(2, calls.get())
                Files.writeString(data.resolve("graph-connection.json"), "{\"workspaceRoot\":\"/wrong\",\"url\":\"http://127.0.0.1:1/mcp\"}")
                assertFailsWith<IllegalArgumentException> { bridge.exchange("{}") }
            }
        } finally { first.stop(0); next.stop(0); root.deleteRecursively(); data.toFile().deleteRecursively() }
        Unit
    }

    @Test fun aDifferentWorkspaceIsRejectedBeforeAnyMcpRequest() = runBlocking {
        val root = Files.createTempDirectory("graph-binding").toFile()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/workspace/identity") { exchange ->
            val bytes = """{"workspaceRoot":"/another/project"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/mcp") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(202, -1); exchange.close() }
        server.start()
        try {
            WorkspaceGraphBridge(root, "http://127.0.0.1:${server.address.port}/mcp").use {
                assertFailsWith<IllegalArgumentException> { it.exchange("{}") }
                assertEquals(0, calls.get())
            }
        } finally { server.stop(0); root.deleteRecursively() }
    }
}
