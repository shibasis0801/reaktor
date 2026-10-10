@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.shibasis.reaktor.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.js.Promise

class WebMcpAdapter<Controller : ToolNode>(controller: Controller) : ToolAdapter<Controller>(controller) {
    private val registration: dynamic = js("new AbortController()")
    private var registered = false

    suspend fun register(context: dynamic): Boolean {
        requireController()
        if (context == null || jsTypeOf(context.registerTool) != "function") return false
        check(!registered) { "WebMCP tools are already registered" }
        registered = true
        try {
            for (definition in definitions) {
                val tool: dynamic = js("({})")
                tool.name = definition.name
                tool.description = definition.description
                definition.title?.let { tool.title = it }
                tool.inputSchema = JSON.parse<dynamic>(definition.inputSchema.toString())
                tool.annotations = js("({})")
                tool.annotations.readOnlyHint = definition.readOnly
                tool.annotations.untrustedContentHint = definition.untrustedContent
                tool.annotations.consequentialHint = definition.consequential
                tool.execute = { input: dynamic, options: dynamic -> invoke(definition.name, input, options?.signal) }
                val options: dynamic = js("({})")
                options.signal = registration.signal
                (context.registerTool(tool, options) as Promise<dynamic>).await()
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
        return true
    }

    private fun invoke(name: String, input: dynamic, signal: dynamic): Promise<dynamic> = scope.promise {
        val job = currentCoroutineContext()[Job]!!
        val abort: (dynamic) -> Unit = { job.cancel(CancellationException("WebMCP execution cancelled")) }
        if (signal?.aborted == true) throw CancellationException("WebMCP execution cancelled")
        signal?.addEventListener("abort", abort)
        try {
            val arguments = Json.parseToJsonElement(JSON.stringify(input)) as? JsonObject
                ?: error("Tool arguments must be an object")
            JSON.parse<dynamic>(execute(name, arguments).webValue().toString())
        } finally {
            signal?.removeEventListener("abort", abort)
        }
    }

    override fun close() {
        if (closed) return
        registration.abort()
        super.close()
    }
}
