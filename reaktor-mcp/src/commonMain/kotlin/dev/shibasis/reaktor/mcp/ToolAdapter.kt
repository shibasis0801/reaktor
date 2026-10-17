package dev.shibasis.reaktor.mcp

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = emptyObjectSchema(),
    val title: String? = null,
    val outputSchema: JsonObject? = null,
    val readOnly: Boolean = false,
    val idempotent: Boolean = false,
    val destructive: Boolean = false,
    val openWorld: Boolean = false,
    val untrustedContent: Boolean = false,
    val consequential: Boolean = false,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) { "Invalid tool name: $name" }
        require(description.isNotBlank()) { "Tool description is required" }
    }
}

data class ToolResult(
    val data: JsonElement,
    val isError: Boolean = false,
    val content: JsonArray? = null,
) {
    companion object {
        fun failure(message: String) = ToolResult(buildJsonObject { put("error", message) }, isError = true)
    }

    fun webValue(): JsonElement = if (!isError && content == null) data else buildJsonObject {
        put("data", data)
        put("isError", isError)
        content?.let { put("content", it) }
    }
}

data class Tool(val definition: ToolDefinition, val execute: suspend (JsonObject) -> ToolResult)

open class ToolNode(graph: Graph, tools: List<Tool>) : BasicNode(graph) {
    private val toolsByName = tools.associateBy { it.definition.name }
    private val closers = mutableSetOf<() -> Unit>()
    var isClosed: Boolean = false
        private set
    val definitions: List<ToolDefinition> get() = toolsByName.values.map { it.definition }

    init { require(toolsByName.size == tools.size) { "Duplicate tool names" } }

    internal fun onClose(close: () -> Unit): () -> Unit {
        check(!isClosed) { "Tool node is closed" }
        closers += close
        return { closers -= close }
    }

    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        check(!isClosed) { "Tool node is closed" }
        val tool = toolsByName[name] ?: error("Unknown tool: $name")
        currentCoroutineContext().ensureActive()
        val invocation = coroutineScope.async { tool.execute(arguments) }
        return try { invocation.await() } finally { invocation.cancel() }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        closers.toList().forEach { it() }
        closers.clear()
        super.close()
    }
}

abstract class ToolAdapter<Controller : ToolNode>(controller: Controller) : Adapter<Controller>(controller), AutoCloseable {
    protected val scope = CoroutineScope(
        controller.coroutineScope.coroutineContext + SupervisorJob(controller.coroutineScope.coroutineContext[Job]),
    )
    protected var closed = false
        private set
    private val detach = controller.onClose { close() }
    val definitions: List<ToolDefinition> get() = requireController().definitions

    protected fun requireController(): Controller {
        check(!closed) { "Tool adapter is closed" }
        return controller?.takeUnless { it.isClosed } ?: error("Tool controller is unavailable")
    }

    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        val invocation = scope.async {
            try {
                requireController().execute(name, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ToolResult.failure(error.message ?: "Tool failed")
            }
        }
        return try { invocation.await() } finally { invocation.cancel() }
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        scope.cancel()
    }
}
