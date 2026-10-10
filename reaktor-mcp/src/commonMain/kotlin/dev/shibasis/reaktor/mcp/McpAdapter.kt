package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.JsonElement

class McpAdapter<Controller : ToolNode>(
    controller: Controller,
    name: String,
    version: String,
    instructions: String = "",
    resources: List<McpReadResource> = emptyList(),
) : ToolAdapter<Controller>(controller) {
    private val protocol = ReaktorMcpServer(name, version, instructions, emptyList(), resources)

    suspend fun handle(body: String): JsonElement? {
        requireController()
        return protocol.handleAsync(body, definitions, ::execute)
    }
}
