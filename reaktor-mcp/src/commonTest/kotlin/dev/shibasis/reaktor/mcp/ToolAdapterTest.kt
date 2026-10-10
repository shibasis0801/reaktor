package dev.shibasis.reaktor.mcp

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolAdapterTest {
    @Test
    fun mcpUsesSharedDefinitionsAndPreservesToolErrors() = runTest {
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val node = ToolNode(graph, listOf(
            Tool(ToolDefinition("read", "Read a value", title = "Read", readOnly = true, idempotent = true)) { ToolResult(JsonPrimitive("value")) },
            Tool(ToolDefinition("fail", "Fail explicitly")) { error("Expected failure") },
        ))
        graph.attach(node)
        val adapter = McpAdapter(node, "test", "1")
        try {
            val listed = adapter.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            assertEquals("Read", listed[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("true", listed[0].jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content)
            val failed = adapter.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fail"}}""")!!.jsonObject["result"]!!.jsonObject
            assertEquals("true", failed["isError"]!!.jsonPrimitive.content)
            assertEquals("Expected failure", failed["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive("value"), adapter.execute("read", JsonObject(emptyMap())).data)
            val invalid = adapter.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read","arguments":[]}}""")!!.jsonObject["error"]!!.jsonObject
            assertEquals("-32602", invalid["code"]!!.jsonPrimitive.content)
        } finally { graph.close(); dependencies.close() }
    }

    @Test
    fun adapterAndGraphClosureCancelActiveTools() = runTest {
        for (closeGraph in listOf(false, true)) {
            val dependencies = koinApplication {}
            val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
            val stopped = CompletableDeferred<Unit>()
            val node = ToolNode(graph, listOf(Tool(ToolDefinition("wait", "Wait until cancelled")) {
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }))
            graph.attach(node)
            val adapter = McpAdapter(node, "test", "1")
            val call = async { adapter.execute("wait", buildJsonObject {}) }
            runCurrent()
            if (closeGraph) graph.close() else adapter.close()
            runCurrent()
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(stopped.isCompleted)
            graph.close()
            dependencies.close()
        }
    }

    @Test
    fun callerCancellationStopsTheGraphOwnedInvocation() = runTest {
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val stopped = CompletableDeferred<Unit>()
        val node = ToolNode(graph, listOf(Tool(ToolDefinition("wait", "Wait until cancelled")) {
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }))
        graph.attach(node)
        val adapter = McpAdapter(node, "test", "1")
        val call = async { adapter.execute("wait", buildJsonObject {}) }
        runCurrent()
        call.cancel()
        runCurrent()
        assertTrue(stopped.isCompleted)
        graph.close()
        dependencies.close()
    }
}
