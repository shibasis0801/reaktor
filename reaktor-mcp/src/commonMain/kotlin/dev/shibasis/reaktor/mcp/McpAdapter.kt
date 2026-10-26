package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.JsonElement

class McpAdapter<Controller : ToolNode>(
    controller: Controller,
    name: String,
    version: String,
    instructions: String = "",
    resources: List<McpReadResource> = emptyList(),
    private val projection: Set<String>? = null,
) : ToolAdapter<Controller>(controller) {
    private val protocol = ReaktorMcpServer(name, version, instructions, emptyList(), resources)

    suspend fun handle(body: String): JsonElement? {
        requireController()
        val visible = definitions.filter { projection == null || it.name in projection }
        return protocol.handleAsync(body, visible) { name, arguments ->
            execute(name, arguments)
        }
    }
}
