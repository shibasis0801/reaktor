package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

internal fun codexActivity(agent: AgentId, item: JsonObject, completed: Boolean): AgentEvent.Activity? {
    fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
    val type = text("type") ?: return null
    val kind = when (type) {
        "commandExecution", "command_execution", "fileChange", "file_change", "patchApply", "patch_apply", "mcpToolCall", "mcp_tool_call", "webSearch", "web_search", "dynamicToolCall" -> ActivityKind.Tool
        "plan", "todo_list" -> ActivityKind.Plan
        "collabAgentToolCall", "collab_agent_tool_call" -> ActivityKind.Agent
        "contextCompaction", "context_compaction" -> ActivityKind.Context
        else -> return null
    }
    val status = when {
        text("status") in listOf("failed", "declined", "errored") || (item["exitCode"] ?: item["exit_code"])?.jsonPrimitive?.intOrNull?.let { it != 0 } == true -> ActivityStatus.Failed
        completed -> ActivityStatus.Completed
        else -> ActivityStatus.Started
    }
    return AgentEvent.Activity(agent, AgentActivityItem(text("id") ?: "${agent.value}-$type", kind,
        text("command") ?: text("tool") ?: text("query") ?: type, status,
        input = (item["arguments"] ?: item["changes"] ?: item["items"])?.toString() ?: text("command"),
        output = text("aggregatedOutput") ?: text("aggregated_output") ?: text("text")
            ?: (item["result"] ?: item["error"] ?: item["agentsStates"])?.toString(),
        parentId = text("parentToolUseId"),
        subjectRefs = (item["changes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull }))
}
