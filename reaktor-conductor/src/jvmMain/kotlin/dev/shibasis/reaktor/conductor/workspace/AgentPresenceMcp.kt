package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun agentPresenceTools(workspace: AgentWorkspace): List<McpTool> {
    val api = AgentPresenceApi()
    fun schema(base: JsonObject) = JsonObject(base + mapOf(
        "properties" to JsonObject(base.getValue("properties").jsonObject + ("by" to BoardActor.serializer().descriptor.jsonSchema())),
        "required" to JsonArray(((base["required"] as? JsonArray).orEmpty() + JsonPrimitive("by")).distinct()),
    ))
    fun page() = workspace.presence.read(workspace.plans.list().mapNotNull { workspace.plans.plan(it.thread) })
    return listOf(
        McpTool("agent_presence", "Read observed agent presence and held work.", schema(api.read.request.descriptor.jsonSchema()), true, true) { args ->
            require(args.keys == setOf("by")) { "Expected the authenticated caller" }
            AgentWorkspaceJson.encodeToJsonElement(AgentPresencePage.serializer(), page())
        },
        McpTool("agent_heartbeat", "Renew only the authenticated agent's presence.", schema(api.heartbeat.request.descriptor.jsonSchema()), false, true) { args ->
            val by = AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), args.getValue("by"))
            val request = AgentWorkspaceJson.decodeFromJsonElement(AgentHeartbeatRequest.serializer(), JsonObject(args - "by"))
            require(request.connected || request.door != null) { "Name the door being disconnected" }
            workspace.presence.heartbeat(workspace.identity(by), request.door, request.connected)
            AgentWorkspaceJson.encodeToJsonElement(AgentPresencePage.serializer(), page())
        },
    )
}
