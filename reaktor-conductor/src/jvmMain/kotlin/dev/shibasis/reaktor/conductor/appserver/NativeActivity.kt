package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

internal fun codexHookActivity(agent: AgentId, run: JsonObject, completed: Boolean): AgentEvent.Activity? {
    val aliases = mapOf("eventName" to "event_name", "sourcePath" to "source_path",
        "handlerType" to "handler_type", "executionMode" to "execution_mode",
        "statusMessage" to "status_message", "durationMs" to "duration_ms")
    fun field(key: String) = run[key] ?: aliases[key]?.let(run::get)
    fun text(key: String) = (field(key) as? JsonPrimitive)?.contentOrNull
    val id = text("id") ?: return null
    val event = text("eventName") ?: return null
    val status = when (text("status")) {
        "failed" -> ActivityStatus.Failed
        "blocked" -> ActivityStatus.Waiting
        "stopped" -> ActivityStatus.Interrupted
        else -> if (completed) ActivityStatus.Completed else ActivityStatus.Started
    }
    val metadata = buildJsonObject {
        listOf("source", "sourcePath", "handlerType", "executionMode").forEach { key ->
            (field(key) as? JsonPrimitive)?.let { put(key, it) }
        }
    }
    val result = buildJsonObject {
        listOf("status", "statusMessage", "durationMs").forEach { key ->
            (field(key) as? JsonPrimitive)?.let { put(key, it) }
        }
        putJsonArray("entries") {
            (run["entries"] as? JsonArray).orEmpty().forEach { value ->
                val entry = value as? JsonObject ?: return@forEach
                addJsonObject {
                    listOf("kind", "text").forEach { key ->
                        (entry[key] as? JsonPrimitive)?.let { put(key, it) }
                    }
                }
            }
        }
    }
    val input = metadata.toString()
    val output = result.toString()
    return AgentEvent.Activity(agent, AgentActivityItem("hook:$id", ActivityKind.Control,
        "Hook $event", status, input = input.take(6000), output = output.take(16000),
        subjectRefs = listOfNotNull(text("sourcePath")), detailTruncated = input.length > 6000 || output.length > 16000))
}

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
        nativeAgents = ((item["agentsStates"] ?: item["agents_states"]) as? JsonObject).orEmpty().map { (id, value) ->
            val state = value as? JsonObject
            NativeAgentState(id, text("senderThreadId"), status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: (value as? JsonPrimitive)?.contentOrNull ?: "unknown",
                output = state?.get("message")?.jsonPrimitive?.contentOrNull?.take(6000))
        }.ifEmpty { ((item["receiverThreadIds"] ?: item["receiver_thread_ids"]) as? JsonArray).orEmpty().map {
            NativeAgentState(it.jsonPrimitive.content, text("senderThreadId"))
        } },
        subjectRefs = (item["changes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull }))
}
