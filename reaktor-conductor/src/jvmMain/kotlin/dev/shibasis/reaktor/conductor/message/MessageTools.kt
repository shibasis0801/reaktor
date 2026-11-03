package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.workspace.*
import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal fun messageTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("$key is required")
    fun JsonObject.optional(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.by() = ConductorJson.decodeFromJsonElement(BoardActor.serializer(), getValue("by"))
    fun JsonObject.only(vararg keys: String) { require(this.keys.all { it in keys }) { "Unknown message arguments" } }
    val by = "by" to objectSchema(mapOf("kind" to enumSchema("Authenticated caller", BoardActorKind.entries.map { it.name }),
        "name" to stringSchema("Caller name"), "session" to stringSchema("Caller session")), listOf("kind", "name"))
    val id = "id" to stringSchema("Message id")
    return listOf(
        McpTool("agent_message_send", "Persist a message addressed to an agent, seat or plan. Sent means stored; delivered means a harness accepted it; read requires acknowledgment.",
            objectSchema(mapOf(by, "id" to stringSchema("Stable client request id"), "to" to objectSchema(mapOf(
                "agent" to stringSchema("Canonical agent key"), "plan" to stringSchema("Plan thread"), "seat" to stringSchema("Seat on the plan"))),
                "kind" to enumSchema("Message kind", MessageKind.entries.map { it.name }), "body" to stringSchema("At most 8192 UTF-8 bytes"),
                "item" to stringSchema("Related plan item"), "replyTo" to stringSchema("Message being answered")), listOf("by", "id", "to", "kind", "body")), false, true) { args ->
            args.only("by", "id", "to", "kind", "body", "item", "replyTo")
            val to = args.getValue("to").jsonObject
            val recipient = when {
                "agent" in to -> { to.only("agent"); MessageRecipient.Agent(to.text("agent")) }
                "seat" in to -> { to.only("plan", "seat"); MessageRecipient.Seat(to.text("plan"), to.text("seat")) }
                else -> { to.only("plan"); MessageRecipient.Plan(to.text("plan")) }
            }
            val actor = args.by().let { by -> if (by.kind == BoardActorKind.Person) workspace.planIdentity(by) else workspace.identity(by).actor }
            val from = if (actor.kind == BoardActorKind.Person) "person:${actor.name}" else requireNotNull(actor.agent)
            val draft = SendMessage(args.text("id"), from, recipient, MessageKind.valueOf(args.text("kind")), args.text("body"), args.optional("item"), args.optional("replyTo"))
            ConductorJson.encodeToJsonElement(Message.serializer(), runBlocking { workspace.messages.send(actor, draft) })
        },
        McpTool("agent_message_list", "Read this caller's mailbox, including unread and delivery receipts.",
            objectSchema(mapOf(by, "unreadOnly" to buildJsonObject { put("type", "boolean") },
                "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) }), listOf("by")), true, true) { args ->
            args.only("by", "unreadOnly", "limit")
            ConductorJson.encodeToJsonElement(ListSerializer(ReceivedMessage.serializer()), runBlocking {
                workspace.messages.inbox(args.by(), args["unreadOnly"]?.jsonPrimitive?.boolean ?: true, args["limit"]?.jsonPrimitive?.int ?: 50)
            })
        },
        McpTool("agent_message_page", "Read a bounded page of this caller's mailbox, continuing after a visible retained message id.",
            objectSchema(mapOf(by, "unreadOnly" to buildJsonObject { put("type", "boolean") },
                "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) },
                "after" to stringSchema("Last message id from the previous page")), listOf("by")), true, true) { args ->
            args.only("by", "unreadOnly", "limit", "after")
            ConductorJson.encodeToJsonElement(InboxPage.serializer(), runBlocking {
                workspace.messages.page(args.by(), args["unreadOnly"]?.jsonPrimitive?.boolean ?: true,
                    args["limit"]?.jsonPrimitive?.int ?: 50, args.optional("after"))
            })
        },
        McpTool("agent_message_read", "Read a message in the authenticated agent's mailbox.", objectSchema(mapOf(by, id), listOf("by", "id")), true, true) { args ->
            args.only("by", "id")
            ConductorJson.encodeToJsonElement(ReceivedMessage.serializer(), runBlocking { workspace.messages.read(args.by(), args.text("id")) })
        },
        McpTool("agent_message_ack", "Record that this agent read a message. Repeats have no second effect.", objectSchema(mapOf(by, id), listOf("by", "id")), false, true) { args ->
            args.only("by", "id")
            ConductorJson.encodeToJsonElement(MessageReceipt.serializer(), runBlocking { workspace.messages.ack(args.by(), args.text("id")) })
        },
    )
}
