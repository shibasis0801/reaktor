package dev.shibasis.reaktor.tooling.mcp

import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.service.contract.Served
import dev.shibasis.reaktor.service.contract.listen
import dev.shibasis.reaktor.service.contract.toRouter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStdioRelayTest {
    private val seen = CopyOnWriteArrayList<JsonObject>()
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private val hosted = ConcurrentHashMap.newKeySet<String>().apply { add("bestbuds") }
    private val server = ReaktorMcpServer("workspace", "1", "", listOf(
        McpTool("whoami", "Echoes who called", buildJsonObject { put("type", "object") }, readOnly = true, idempotent = true,
            executeWithMeta = { _, metadata -> seen += metadata; JsonPrimitive("ok") }) { JsonPrimitive("ok") }))
    private val transport = McpStreamableHttp { scope ->
        if (scope !in hosted) null else McpHttpScope(server,
            admit = { arrival -> buildJsonObject { put("reaktor/caller", arrival.header("Reaktor-Principal") ?: "anonymous") } },
            opened = { opened += it.id }, closed = { closed += it.id })
    }
    private val listening = Served(transport).toRouter().listen("127.0.0.1", 0)

    @AfterTest fun stop() {
        transport.close()
        listening.close()
    }

    private inner class Client(scope: String, admit: () -> Unit = {}) : AutoCloseable {
        private val input = PipedOutputStream()
        private val source = PipedInputStream(input, 1 shl 16)
        private val lines = LinkedBlockingQueue<String>()
        private val relay = McpStdioRelay(URI("http://127.0.0.1:${listening.port}/mcp/$scope?seat=claude-code"), mapOf("Reaktor-Principal" to "claude:one"), admit)
        private val thread = Thread.ofPlatform().start {
            relay.run(source, object : OutputStream() {
                private val pending = StringBuilder()
                override fun write(value: Int) {
                    if (value == '\n'.code) { lines += pending.toString(); pending.clear() } else pending.append(value.toChar())
                }
            })
        }

        fun send(line: String) { input.write((line + "\n").toByteArray()); input.flush() }
        fun next(): JsonObject = Json.parseToJsonElement(assertNotNull(lines.poll(20, TimeUnit.SECONDS), "the relay did not answer")).jsonObject
        fun quiet(): String? = lines.poll(300, TimeUnit.MILLISECONDS)
        fun call(id: Int): JsonObject {
            send("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"whoami","arguments":{}}}""")
            return next()
        }

        override fun close() {
            input.close()
            thread.join(10000)
        }
    }

    private val greeting = """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"relay-test","version":"1"}}}"""

    @Test fun aStdioClientReachesTheEndpointWithItsHeadersAndEndsItsSession() {
        Client("bestbuds").use { client ->
            client.send(greeting)
            assertEquals("workspace", client.next().getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
            client.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertNull(client.quiet())
            assertEquals(1, client.call(1).getValue("id").jsonPrimitive.content.toInt())
            assertEquals("claude:one", seen.single().getValue("reaktor/caller").jsonPrimitive.content)
        }
        assertEquals(opened, closed)
    }

    @Test fun aRestartedHostIsGreetedAgainAndTheCallGoesThrough() {
        Client("bestbuds").use { client ->
            client.send(greeting)
            client.next()
            transport.release("bestbuds")
            val answered = client.call(2)
            assertEquals(2, answered.getValue("id").jsonPrimitive.content.toInt())
            assertTrue("result" in answered, answered.toString())
            assertEquals(2, opened.size)
        }
    }

    @Test fun aCallAfterAFailedRenewalGreetsAgainOnceTheWorkspaceIsBack() {
        Client("bestbuds").use { client ->
            client.send(greeting)
            client.next()
            hosted -= "bestbuds"
            transport.expire(0)
            assertTrue("error" in client.call(3))
            hosted += "bestbuds"
            val answered = client.call(4)
            assertTrue("result" in answered, answered.toString())
            assertEquals(2, opened.size)
        }
    }

    @Test fun anUnhostedWorkspaceIsAdmittedOnceAndAFailedCallStillGetsAnAnswer() {
        var admissions = 0
        Client("manna", admit = { admissions++; hosted += "manna" }).use { client ->
            client.send(greeting)
            assertTrue("result" in client.next())
            assertEquals(1, admissions)
        }
        Client("elsewhere").use { client ->
            client.send(greeting)
            val refused = client.next()
            assertEquals(0, refused.getValue("id").jsonPrimitive.content.toInt())
            assertTrue(refused.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("404"), refused.toString())
        }
    }
}
