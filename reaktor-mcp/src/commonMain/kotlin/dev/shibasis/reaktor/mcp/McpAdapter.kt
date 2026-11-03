package dev.shibasis.reaktor.mcp

import dev.shibasis.reaktor.service.SpanSink
import kotlinx.serialization.json.JsonElement

class McpAdapter<Controller : ToolNode>(
    controller: Controller,
    name: String,
    version: String,
    instructions: String = "",
    resources: List<McpReadResource> = emptyList(),
    private val projection: Set<String>? = null,
    spanSink: SpanSink? = null,
) : ToolAdapter<Controller>(controller) {
    private val protocol = ReaktorMcpServer(name, version, instructions, emptyList(), resources, spanSink = spanSink)

    suspend fun handle(body: String): JsonElement? {
        requireController()
        val visible = definitions.filter { projection == null || it.name in projection }
        return protocol.handleAsyncWithMeta(body, visible) { name, arguments, metadata ->
            execute(name, arguments, metadata)
        }
    }
}
