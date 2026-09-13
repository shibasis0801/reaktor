package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeRequestTest {
    private fun request(method: String, params: String = "{}", generation: String = "session-a") =
        codexRequest(JsonRpcInbound.ServerRequest(JsonPrimitive(12), method, Json.parseToJsonElement(params).jsonObject), generation)!!

    @Test fun currentApprovalsUseCurrentDecisionVocabularyAndUniqueSessionIdentity() {
        listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval").forEach { method ->
            val ask = request(method, """{"turnId":"turn-a","command":"echo hello"}""")
            assertEquals("accept", ask.response(AgentDecision.Approve).getValue("decision").jsonPrimitive.content)
            assertEquals("decline", ask.response(AgentDecision.Deny()).getValue("decision").jsonPrimitive.content)
            assertNotEquals(ask.pending.id, request(method, generation = "session-b").pending.id)
            assertEquals("turn-a", ask.pending.turnId)
            assertFails { ask.response(AgentDecision.Answer("yes")) }
        }
    }
    @Test fun questionsPreserveProviderIdsAndRequireEveryAnswer() {
        val ask = request("item/tool/requestUserInput", """{"questions":[{"id":"layout","question":"Tabs?","options":[{"label":"Top"}]},{"id":"theme","question":"Theme?"}]}""")
        assertEquals(listOf("Top"), ask.pending.questions.first().options)
        assertFails { ask.response(AgentDecision.Approve) }
        assertFails { ask.response(AgentDecision.Answers(mapOf("layout" to listOf("Top")))) }
        val result = ask.response(AgentDecision.Answers(mapOf("layout" to listOf("Top"), "theme" to listOf("Dark"))))
        assertEquals(setOf("layout", "theme"), result.getValue("answers").jsonObject.keys)
    }
    @Test fun permissionGrantIsExactlyWhatWasRequested() {
        val ask = request("item/permissions/requestApproval", """{"permissions":{"fileSystem":{"read":["/tmp/example"]}}}""")
        assertEquals(ask.wire.params["permissions"], ask.response(AgentDecision.Approve)["permissions"])
        assertEquals(buildJsonObject {}, ask.response(AgentDecision.Deny())["permissions"])
        assertEquals(JsonPrimitive("turn"), ask.response(AgentDecision.Approve)["scope"])
    }
    @Test fun elicitationRequiresTypedFieldsRatherThanAnApproval() {
        val ask = request("mcpServer/elicitation/request", """{"requestedSchema":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}}""")
        assertFails { ask.response(AgentDecision.Approve) }
        assertFails { ask.response(AgentDecision.Form(buildJsonObject { put("count", "bad") })) }
        assertEquals(JsonPrimitive("accept"), ask.response(AgentDecision.Form(buildJsonObject { put("count", 2) }))["action"])
    }
    @Test fun claudeQuestionsPreserveInputAndUseNativeQuestionTextKeys() {
        val ask = claudeRequest(Json.parseToJsonElement("""{"request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Which layout?","options":[{"label":"Tabs"}]}]}}}""").jsonObject, "session", "turn")!!
        assertFails { ask.response(AgentDecision.Approve) }
        val response = ask.response(AgentDecision.Answers(mapOf("q0" to listOf("Tabs")))).getValue("response").jsonObject.getValue("response").jsonObject
        val input = response.getValue("updatedInput").jsonObject
        assertEquals(ask.input["questions"], input["questions"])
        assertEquals(JsonPrimitive("Tabs"), input.getValue("answers").jsonObject["Which layout?"])
    }
}
