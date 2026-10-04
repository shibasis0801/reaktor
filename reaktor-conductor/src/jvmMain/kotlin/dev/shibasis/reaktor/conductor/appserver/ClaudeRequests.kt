package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

internal data class ClaudeRequest(val id: String, val input: JsonObject, val pending: PendingRequest) {
    fun response(decision: AgentDecision): JsonObject {
        val answer = when {
            decision is AgentDecision.Deny -> buildJsonObject {
                put("behavior", "deny"); put("message", decision.reason ?: "Declined by the operator")
            }
            pending.kind == RequestKind.Input -> {
                val answers = when (decision) {
                    is AgentDecision.Answers -> decision.values
                    is AgentDecision.Answer -> {
                        require(pending.questions.size == 1) { "Answer each question by id" }
                        mapOf(pending.questions.single().id to listOf(decision.text))
                    }
                    else -> error("This request needs answers, not approval")
                }
                require(answers.keys == pending.questions.map { it.id }.toSet()) { "Question ids do not match" }
                buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", JsonObject(input + ("answers" to buildJsonObject {
                        pending.questions.forEach { put(it.title, answers.getValue(it.id).joinToString(", ")) }
                    })))
                }
            }
            decision is AgentDecision.Approve -> buildJsonObject {
                put("behavior", "allow"); put("updatedInput", input)
            }
            else -> error("This request needs approval or denial")
        }
        return buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success"); put("request_id", id); put("response", answer)
            }
        }
    }
}

internal fun claudeRequest(message: JsonObject, generation: String, turnId: String?): ClaudeRequest? {
    val id = (message["request_id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val request = message.objectOrEmpty("request")
    if ((request["subtype"] as? JsonPrimitive)?.contentOrNull != "can_use_tool") return null
    val input = request.objectOrEmpty("input")
    val tool = (request["tool_name"] as? JsonPrimitive)?.contentOrNull ?: "tool"
    val questions = if (tool == "AskUserQuestion") (input["questions"] as? JsonArray).orEmpty().mapIndexed { index, value ->
        val q = value.jsonObject
        PendingQuestion("q$index", q.getValue("question").jsonPrimitive.content,
            (q["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull })
    } else emptyList()
    return ClaudeRequest(id, input, PendingRequest(
        "$generation:$id", if (tool == "AskUserQuestion") RequestKind.Input else RequestKind.Permission,
        if (questions.isNotEmpty()) "Answer Claude" else "Use $tool", input.toString(),
        providerRequestId = id, turnId = turnId, questions = questions,
    ))
}
