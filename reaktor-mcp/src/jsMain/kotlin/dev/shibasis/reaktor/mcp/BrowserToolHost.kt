@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.shibasis.reaktor.mcp

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

@JsExport
fun createBrowserToolGraph(label: String): Graph {
    val dependencies = koinApplication {}
    return object : Graph(dependencyAdapter = KoinDependencyAdapter(dependencies), label = label) {
        override fun close() {
            super.close()
            dependencies.close()
        }
    }
}

@JsExport
class BrowserToolHost(
    graph: Graph,
    definitionsJson: String,
    execute: (String, String, dynamic) -> Promise<String>,
) {
    private val node = ToolNode(graph, (Json.parseToJsonElement(definitionsJson) as JsonArray).map { entry ->
        val value = entry as JsonObject
        fun string(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
        fun flag(key: String) = (value[key] as? JsonPrimitive)?.booleanOrNull ?: false
        val definition = ToolDefinition(
            name = string("name") ?: error("Tool name is required"),
            description = string("description") ?: error("Tool description is required"),
            inputSchema = value["inputSchema"] as? JsonObject ?: emptyObjectSchema(),
            title = string("title"), outputSchema = value["outputSchema"] as? JsonObject,
            readOnly = flag("readOnly"), idempotent = flag("idempotent"),
            destructive = flag("destructive"), openWorld = flag("openWorld"),
            untrustedContent = flag("untrustedContent"), consequential = flag("consequential"),
        )
        Tool(definition) { arguments ->
            suspendCancellableCoroutine { continuation ->
                val abort: dynamic = js("new AbortController()")
                continuation.invokeOnCancellation { abort.abort() }
                try {
                    execute(definition.name, arguments.toString(), abort.signal).then({ data ->
                        if (continuation.isActive) {
                            try { continuation.resume(ToolResult(Json.parseToJsonElement(data))) }
                            catch (error: Throwable) { continuation.resumeWithException(error) }
                        }
                    }, { error ->
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.message ?: "Browser tool failed", error))
                    })
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.message ?: "Browser tool failed", error))
                }
            }
        }
    })
    private val browser = WebMcpAdapter(node)
    private val mcp = McpAdapter(node, "reaktor-browser-tools", "1.0.0")

    init { graph.attach(node) }

    val isClosed: Boolean get() = node.isClosed

    fun register(context: dynamic = js("globalThis.document == null ? undefined : globalThis.document.modelContext")): Promise<Boolean> =
        node.coroutineScope.promise { browser.register(context) }

    fun handleMcp(body: String): Promise<String?> = node.coroutineScope.promise { mcp.handle(body)?.toString() }

    fun close() { node.graph.detach(node) }
}
