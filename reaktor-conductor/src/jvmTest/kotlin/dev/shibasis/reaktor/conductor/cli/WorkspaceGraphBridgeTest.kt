package dev.shibasis.reaktor.conductor.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkspaceGraphBridgeTest {
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
