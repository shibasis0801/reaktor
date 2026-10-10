package dev.shibasis.reaktor.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebMcpAdapterTest {
    @Test
    fun javascriptFailuresBecomeExplicitToolErrors() = runTest {
        val graph = createBrowserToolGraph("test")
        val host = BrowserToolHost(graph, """[{"name":"fail","description":"Fail in JavaScript"}]""") { _, _, _ ->
            val failure: dynamic = js("new Error('JavaScript failure')")
            Promise.reject(failure)
        }
        val reply = Json.parseToJsonElement(host.handleMcp("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"fail"}}""").await()!!).jsonObject["result"]!!.jsonObject
        assertEquals("true", reply["isError"]!!.jsonPrimitive.content)
        assertEquals("JavaScript failure", reply["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        graph.close()
    }

    @Test
    fun registrationPublishesMetadataAndGraphCloseAbortsIt() = runTest {
        val graph = createBrowserToolGraph("test")
        val node = ToolNode(graph, listOf(Tool(ToolDefinition("read", "Read a value", readOnly = true, untrustedContent = true)) { ToolResult(JsonPrimitive("value")) }))
        graph.attach(node)
        val adapter = WebMcpAdapter(node)
        val context: dynamic = js("({})")
        var tool: dynamic = null
        var registration: dynamic = null
        context.registerTool = { value: dynamic, options: dynamic ->
            tool = value
            registration = options.signal
            Promise.resolve<dynamic>(null)
        }
        assertFalse(adapter.register(null))
        assertTrue(adapter.register(context))
        assertEquals("read", tool.name as String)
        assertTrue(tool.annotations.readOnlyHint as Boolean)
        assertTrue(tool.annotations.untrustedContentHint as Boolean)
        val result = (tool.execute(js("({})"), js("({})")) as Promise<dynamic>).await()
        assertEquals("value", result as String)
        graph.close()
        assertTrue(registration.aborted as Boolean)
    }

    @Test
    fun executionAbortCancelsHandlerAndFailedRegistrationRollsBack() = runTest {
        val graph = createBrowserToolGraph("test")
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val node = ToolNode(graph, listOf(Tool(ToolDefinition("wait", "Wait")) {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }))
        graph.attach(node)
        val adapter = WebMcpAdapter(node)
        val context: dynamic = js("({})")
        var tool: dynamic = null
        var registration: dynamic = null
        context.registerTool = { value: dynamic, options: dynamic ->
            tool = value; registration = options.signal
            Promise.resolve<dynamic>(null)
        }
        adapter.register(context)
        val abort: dynamic = js("new AbortController()")
        val options: dynamic = js("({})")
        options.signal = abort.signal
        val call = tool.execute(js("({})"), options) as Promise<dynamic>
        started.await()
        abort.abort()
        assertFailsWith<CancellationException> { call.await() }
        stopped.await()
        adapter.close()
        assertTrue(registration.aborted as Boolean)
        val failed = WebMcpAdapter(node)
        context.registerTool = { _: dynamic, value: dynamic ->
            registration = value.signal
            Promise.reject(IllegalStateException("Registration rejected"))
        }
        assertFailsWith<IllegalStateException> { failed.register(context) }
        assertTrue(registration.aborted as Boolean)
        graph.close()
    }
}
