package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

internal data class CodexRequest(val wire: JsonRpcInbound.ServerRequest, val pending: PendingRequest) {
    fun response(decision: AgentDecision): JsonObject = when (wire.method) {
        "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> buildJsonObject {
            put("decision", approval(decision, "accept", "decline"))
        }
        "execCommandApproval", "applyPatchApproval" -> buildJsonObject {
            put("decision", approval(decision, "approved", "denied"))
        }
        "item/tool/requestUserInput" -> buildJsonObject {
            val values = when (decision) {
                is AgentDecision.Answers -> decision.values
                is AgentDecision.Answer -> {
                    require(pending.questions.size == 1) { "Answer each question by id" }
                    mapOf(pending.questions.single().id to listOf(decision.text))
                }
                is AgentDecision.Deny -> pending.questions.associate { it.id to emptyList<String>() }
                else -> error("This request needs answers, not approval")
            }
            require(values.keys == pending.questions.map { it.id }.toSet()) { "Question ids do not match the pending request" }
            putJsonObject("answers") { values.forEach { (id, answers) ->
                putJsonObject(id) { put("answers", JsonArray(answers.map(::JsonPrimitive))) }
            } }
        }
        "item/permissions/requestApproval" -> buildJsonObject {
            require(decision is AgentDecision.Approve || decision is AgentDecision.Deny)
            put("permissions", if (decision is AgentDecision.Approve) wire.params.objectOrEmpty("permissions") else buildJsonObject {})
            put("scope", "turn")
        }
        "mcpServer/elicitation/request" -> buildJsonObject {
            when (decision) {
                is AgentDecision.Deny -> { put("action", "decline"); put("content", JsonNull) }
                is AgentDecision.Form -> {
                    require(pending.schema != null) { "This request is not a form" }
                    validateForm(pending.schema, decision.values)
                    put("action", "accept"); put("content", decision.values)
                }
                is AgentDecision.Approve -> {
                    require(pending.url != null) { "This request needs form values" }
                    put("action", "accept"); put("content", JsonNull)
                }
                else -> error("This request needs structured form values")
            }
        }
        else -> error("Unsupported request ${wire.method}")
    }
}

private fun approval(decision: AgentDecision, accept: String, deny: String): String = when (decision) {
    AgentDecision.Approve -> accept
    is AgentDecision.Deny -> deny
    else -> error("This request needs approval or denial")
}

internal fun codexRequest(wire: JsonRpcInbound.ServerRequest, generation: String): CodexRequest? {
    val params = wire.params
    fun string(name: String) = (params[name] as? JsonPrimitive)?.contentOrNull
    val kind = when (wire.method) {
        "item/commandExecution/requestApproval", "execCommandApproval" -> RequestKind.CommandApproval
        "item/fileChange/requestApproval", "applyPatchApproval" -> RequestKind.PatchApproval
        "item/permissions/requestApproval" -> RequestKind.Permission
        "item/tool/requestUserInput" -> RequestKind.Input
        "mcpServer/elicitation/request" -> RequestKind.Elicitation
        else -> return null
    }
    val questions = (params["questions"] as? JsonArray).orEmpty().map { element ->
        val question = element.jsonObject
        PendingQuestion(question.getValue("id").jsonPrimitive.content,
            (question["question"] ?: question["title"] ?: question["header"])?.jsonPrimitive?.contentOrNull ?: "Answer a question",
            (question["options"] as? JsonArray).orEmpty().mapNotNull {
                ((it as? JsonObject)?.get("label") ?: it as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
            })
    }
    val id = wire.id.jsonPrimitive.content
    return CodexRequest(wire, PendingRequest(
        id = "$generation:$id", kind = kind,
        title = string("message") ?: string("reason") ?: when (kind) {
            RequestKind.CommandApproval -> "Run a command"
            RequestKind.PatchApproval -> "Change files"
            RequestKind.Permission -> "Grant permissions for this turn"
            RequestKind.Input -> "Answer the agent"
            RequestKind.Elicitation -> "A tool needs input"
        },
        scope = string("command") ?: string("grantRoot") ?: params["permissions"]?.toString()
            ?: params["fileChanges"]?.toString(),
        providerRequestId = id, turnId = string("turnId"), questions = questions,
        schema = params["requestedSchema"] as? JsonObject, url = string("url"),
    ))
}

internal fun validateForm(schema: JsonObject, values: JsonObject) {
    val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    require(values.keys.containsAll(required)) { "Required form fields are missing" }
    val properties = schema.objectOrEmpty("properties")
    require(values.keys.all { it in properties }) { "Unknown form field" }
    values.forEach { (name, value) ->
        val field = properties.getValue(name).jsonObject
        val type = (field["type"] as? JsonPrimitive)?.contentOrNull
        val primitive = value as? JsonPrimitive
        require(when (type) {
            "string" -> primitive?.isString == true
            "boolean" -> primitive?.booleanOrNull != null && !primitive.isString
            "integer" -> primitive?.longOrNull != null && !primitive.isString
            "number" -> primitive?.doubleOrNull != null && !primitive.isString
            "array" -> value is JsonArray
            else -> false
        }) { "Unsupported or invalid form field: $name" }
        (field["enum"] as? JsonArray)?.let { require(value in it) { "Invalid choice for $name" } }
    }
}
