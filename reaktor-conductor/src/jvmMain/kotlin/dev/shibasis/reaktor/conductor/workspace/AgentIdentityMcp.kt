package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal fun agentIdentityTools(workspace: AgentWorkspace): List<McpTool> {
    val api = AgentIdentityApi()
    fun schema(base: JsonObject) = JsonObject(base + mapOf(
        "properties" to JsonObject(base.getValue("properties").jsonObject + ("by" to BoardActor.serializer().descriptor.jsonSchema())),
        "required" to JsonArray(((base["required"] as? JsonArray).orEmpty() + JsonPrimitive("by")).distinct()),
    ))
    fun actor(args: JsonObject) = AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), args.getValue("by"))
    return listOf(
        McpTool("agent_identity", "Read the authenticated agent's profile tuple and binding revision.", schema(api.identity.request.descriptor.jsonSchema()), true, true) { args ->
            require(args.keys == setOf("by")) { "Expected the authenticated caller" }
            AgentWorkspaceJson.encodeToJsonElement(AgentIdentity.serializer(), workspace.identity(actor(args)))
        },
        McpTool("agent_bind", "Bind the authenticated native agent to a profile at the observed binding revision.", schema(api.bind.request.descriptor.jsonSchema()), false, false) { args ->
            require((args.keys - setOf("by", "profile", "expectedRevision", "session")).isEmpty()) { "Unknown binding arguments" }
            AgentWorkspaceJson.encodeToJsonElement(AgentIdentity.serializer(), workspace.bindIdentity(actor(args),
                args.getValue("profile").jsonPrimitive.content, args.getValue("expectedRevision").jsonPrimitive.long,
                (args["session"] as? JsonPrimitive)?.content?.takeUnless { it == "null" }))
        },
    ) + agentPresenceTools(workspace)
}
