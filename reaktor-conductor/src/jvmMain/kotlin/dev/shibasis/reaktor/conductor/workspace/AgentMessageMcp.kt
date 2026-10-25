package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

internal fun agentMessageTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("$key is required")
    fun JsonObject.optional(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.only(vararg keys: String) {
        require((this.keys - keys.toSet()).isEmpty()) { "Unknown message arguments" }
    }
    fun JsonObject.caller() = workspace.taskGraphCaller(text("graph"),
        AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), getValue("by")))
    fun receipt(value: AgentMessage, records: TaskCoordination) = buildJsonObject {
        val events = records.messageEvents.filter { it.message == value.id }
        put("message", AgentWorkspaceJson.encodeToJsonElement(AgentMessage.serializer(), value))
        put("state", (events.maxByOrNull { it.state.ordinal }?.state ?: AgentMessageState.Accepted).name)
        put("events", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentMessageEvent.serializer()), events))
    }
    val graph = "graph" to stringSchema("Task graph id")
    val id = "id" to stringSchema("Retained message id")
    val by = "by" to objectSchema(mapOf(
        "kind" to enumSchema("Authenticated author", BoardActorKind.entries.map { it.name }),
        "name" to stringSchema("Authenticated seat or person"),
        "session" to stringSchema("Authenticated session reference"),
    ), listOf("kind", "name"))
    val integer = buildJsonObject { put("type", "integer"); put("minimum", 0) }
    return listOf(
        McpTool("agent_message_send", "Persist a message from the authenticated graph lead or current task owner. A client id and expectedRevision make identical retries return the original message. Accepted means persisted, not delivered.",
            objectSchema(mapOf(graph, "id" to stringSchema("Client request id, stable across retries; 1 to 200 letters, digits, dots, underscores, colons or hyphens"),
                "expectedRevision" to integer,
                "recipient" to objectSchema(mapOf("task" to stringSchema("Recipient task in this graph"), "session" to stringSchema("Session recorded by this workspace owner"))),
                "kind" to enumSchema("Message kind", AgentMessageKind.entries.map { it.name }),
                "body" to stringSchema("Message body, at most 8192 UTF-8 bytes"),
                "task" to stringSchema("Author task, if applicable"), "attempt" to integer,
                "thread" to stringSchema("Retained thread id"), "replyTo" to stringSchema("Retained message being answered"),
                "artifacts" to buildJsonObject { put("type", "array"); put("items", stringSchema("Artifact reference")) }, by),
                listOf("graph", "id", "expectedRevision", "recipient", "kind", "body", "by")), false, true) { args ->
            args.only("graph", "id", "expectedRevision", "recipient", "kind", "body", "task", "attempt", "thread", "replyTo", "artifacts", "by")
            val caller = args.caller()
            val draft = AgentMessage(args.text("graph"), AgentWorkspaceJson.decodeFromJsonElement(AgentRecipient.serializer(), args.getValue("recipient")),
                AgentMessageKind.valueOf(args.text("kind")), args.text("body"), thread = args.optional("thread"), replyTo = args.optional("replyTo"),
                task = args.optional("task"), attempt = args["attempt"]?.jsonPrimitive?.int,
                artifacts = args["artifacts"]?.let { AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(String.serializer()), it) }.orEmpty(),
                requestId = args.text("id"), requestRevision = args["expectedRevision"]?.jsonPrimitive?.long)
            val value = workspace.sendMessage(draft, caller)
            receipt(value, workspace.board.coordination(value.graph))
        },
        McpTool("agent_message_list", "List up to 50 retained message headers visible to the authenticated author, recipient, graph lead or person. Bodies are read separately; an expired cursor refuses.",
            objectSchema(mapOf(graph, "after" to stringSchema("Last visible retained message id from the previous page"),
                "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 50) }, by), listOf("graph", "by")), true, true) { args ->
            args.only("graph", "after", "limit", "by")
            val records = workspace.board.messageRecords(args.text("graph"), args.caller())
            val limit = args["limit"]?.jsonPrimitive?.int ?: 20
            require(limit in 1..50) { "A message page contains 1 to 50 headers" }
            val start = args.optional("after")?.let { after ->
                val index = records.messages.indexOfFirst { it.id == after }
                require(index >= 0) { "The cursor is not a visible retained message; start a new page" }
                index + 1
            } ?: 0
            val page = records.messages.drop(start).take(limit)
            buildJsonObject {
                put("graph", args.text("graph")); put("revision", workspace.board.graph(args.text("graph")).revision)
                putJsonArray("messages") {
                    page.forEach { value ->
                        add(JsonObject(AgentWorkspaceJson.encodeToJsonElement(AgentMessage.serializer(), value).jsonObject - "body" - "artifacts" + mapOf(
                            "artifactCount" to JsonPrimitive(value.artifacts.size),
                            "state" to JsonPrimitive((records.messageEvents.filter { it.message == value.id }.maxByOrNull { it.state.ordinal }?.state ?: AgentMessageState.Accepted).name))))
                    }
                }
                if (start + page.size < records.messages.size) put("nextAfter", page.last().id)
            }
        },
        McpTool("agent_message_read", "Read one visible retained message with its author, task attempt and recorded receipts.",
            objectSchema(mapOf(graph, id, by), listOf("graph", "id", "by")), true, true) { args ->
            args.only("graph", "id", "by")
            val value = workspace.board.message(args.text("graph"), args.text("id"), args.caller())
            receipt(value, workspace.board.coordination(value.graph))
        },
        McpTool("agent_message_ack", "Record acknowledgment by the recipient or a person. Repeating acknowledgment has no second effect.",
            objectSchema(mapOf(graph, id, by), listOf("graph", "id", "by")), false, true) { args ->
            args.only("graph", "id", "by")
            AgentWorkspaceJson.encodeToJsonElement(AgentMessageEvent.serializer(),
                workspace.board.acknowledgeMessage(args.text("graph"), args.text("id"), args.caller()))
        },
    )
}
