package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import kotlinx.serialization.json.*
import kotlin.test.*

class HangarWorkflowTest {
    @Test fun everyHarnessPointsAtTheSamePlaneOwnedWorkflows() {
        HangarSkill.Vendor.entries.forEach { vendor ->
            val skill = HangarSkill.skill(vendor)
            assertContains(skill, HangarWorkflow.guide)
            HangarWorkflow.prompts.forEach { assertContains(skill, it.name) }
            assertFalse("Never treat a checkpoint" in skill)
        }
    }

    @Test fun allWorkflowsRenderThroughTheProtocolWithExplicitContext() {
        val server = ReaktorMcpServer("workflows", "1", "", emptyList(), prompts = HangarWorkflow.prompts)
        val names = rpc(server, "prompts/list").getValue("result").jsonObject.getValue("prompts").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(setOf("plan-work", "plan-review", "plan-handoff", "session-recall"), names.toSet())
        names.forEach { name ->
            val response = rpc(server, "prompts/get", buildJsonObject {
                put("name", name)
                putJsonObject("arguments") { put("thread", "hangar:launch"); put("item", "resource-proof"); put("to", "agent:codex:recipient") }
            })
            val text = response.getValue("result").jsonObject.getValue("messages").jsonArray.single().jsonObject
                .getValue("content").jsonObject.getValue("text").jsonPrimitive.content
            assertContains(text, "hangar:launch")
            if (name != "session-recall") assertContains(text, "resource-proof")
            assertTrue(text.length in 200..6000)
        }
    }

    @Test fun invalidContextCannotRenderAWorkflow() {
        val server = ReaktorMcpServer("workflows", "1", "", emptyList(), prompts = HangarWorkflow.prompts)
        listOf(
            buildJsonObject { put("name", "plan-work") },
            buildJsonObject { put("name", "plan-work"); putJsonObject("arguments") { put("thread", "hangar:launch"); put("item", "../other") } },
            buildJsonObject { put("name", "session-recall"); putJsonObject("arguments") { put("thread", "other") } },
            buildJsonObject { put("name", "plan-handoff"); putJsonObject("arguments") {
                put("thread", "hangar:launch"); put("item", "item"); put("to", "person:owner")
            } },
        ).forEach { arguments -> assertTrue("error" in rpc(server, "prompts/get", arguments)) }
    }

    private fun rpc(server: ReaktorMcpServer, method: String, params: JsonObject = JsonObject(emptyMap())) =
        server.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", "workflow"); put("method", method); put("params", params) }.toString())!!.jsonObject
}
