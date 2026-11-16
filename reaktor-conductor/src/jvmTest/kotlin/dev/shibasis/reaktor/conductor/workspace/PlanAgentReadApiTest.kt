package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanAgentReadApiTest {
    private val person = BoardActor(BoardActorKind.Person, "Fixture owner")
    private val agent = BoardActor(BoardActorKind.Agent, "Fixture agent", "codex:bounded")
    private fun fixture(action: (AgentWorkspace) -> Unit) {
        val root = Files.createTempDirectory("plan-agent-read").toFile()
        try { fixtureWorkspace(root, root.toPath().resolve("state"), emptyMap()).use(action) }
        finally { root.deleteTreeSafely(within = root.parentFile) }
    }
    private fun args(extra: JsonObject = JsonObject(emptyMap())) = JsonObject(buildJsonObject {
        put("thread", "hangar:bounded")
        put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), agent))
    } + extra)

    @Test fun anAgentsDefaultReadIsBoundedInEncodedUtf8AndPagesEveryItemOnce() = fixture { workspace ->
        List(160) { PlanOp.Add("Task $it", key = "p$it", detail = "évidence ".repeat(600)) }.chunked(100).forEach { ops ->
            workspace.plans.write("hangar:bounded", PlanPatch(title = "Bounded", ops = ops), person)
        }
        val read = agentPlanTools(workspace).single { it.name == "agent_plan_read" }
        var reply = read.invoke(args()).jsonObject
        val seen = linkedSetOf<String>()
        var pages = 0
        while (true) {
            assertTrue(reply.toString().encodeToByteArray().size <= 65_536, "The complete UTF-8 answer must fit its budget")
            reply.getValue("plan").jsonObject.getValue("items").jsonArray.forEach { assertTrue(seen.add(it.jsonObject.getValue("id").jsonPrimitive.content)) }
            pages++
            val cursor = reply["continuation"]?.jsonPrimitive?.contentOrNull ?: break
            reply = read.invoke(args(buildJsonObject { put("continuation", cursor) })).jsonObject
            assertTrue(pages < 200)
        }
        assertEquals(160, seen.size)
        assertTrue(pages > 1)
    }

    @Test fun aNamedFrameReadRefusesItsCursorAfterAWrite() = fixture { workspace ->
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Bounded", ops = listOf(
            PlanOp.Add("Area", key = "area", kind = PlanKind.Component),
            PlanOp.Add("Outside", key = "outside")) + List(20) { PlanOp.Add("Task $it", key = "p$it", parent = "area", detail = "detail ".repeat(700)) }), person)
        val read = agentPlanTools(workspace).single { it.name == "agent_plan_read" }
        val first = read.invoke(args(buildJsonObject { put("frame", "area"); put("budgetBytes", 8192) })).jsonObject
        assertTrue(first.getValue("plan").jsonObject.getValue("items").jsonArray.none { it.jsonObject["id"]?.jsonPrimitive?.content == "outside" })
        val cursor = first.getValue("continuation").jsonPrimitive.content
        workspace.plans.write("hangar:bounded", PlanPatch(ops = listOf(PlanOp.Update("outside", title = "Changed"))), person)
        val stale = read.invoke(args(buildJsonObject { put("frame", "area"); put("budgetBytes", 8192); put("continuation", cursor) })).jsonObject
        assertEquals(true, stale.getValue("restart").jsonPrimitive.boolean)
        assertTrue(stale.getValue("reason").jsonPrimitive.content.contains("changed"))
    }
    @Test fun anOversizedItemIsReconstructedFromBoundedJsonFragments() = fixture { workspace ->
        val detail = "🧪é".repeat(16000)
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Large item", ops = listOf(PlanOp.Add("Large", key = "large", detail = detail))), person)
        val read = agentPlanTools(workspace).single { it.name == "agent_plan_read" }
        var reply = read.invoke(args(buildJsonObject { put("budgetBytes", 8192) })).jsonObject
        val text = StringBuilder()
        var count = 0
        while (true) {
            assertTrue(reply.toString().encodeToByteArray().size <= 8192)
            val fragment = reply.getValue("fragment").jsonObject
            assertEquals(text.length, fragment.getValue("offset").jsonPrimitive.int)
            text.append(fragment.getValue("text").jsonPrimitive.content)
            count++
            val cursor = reply["continuation"]?.jsonPrimitive?.contentOrNull ?: break
            reply = read.invoke(args(buildJsonObject { put("budgetBytes", 8192); put("continuation", cursor) })).jsonObject
            assertTrue(count < 200)
        }
        val item = AgentWorkspaceJson.decodeFromString(PlanItem.serializer(), text.toString())
        assertEquals("large", item.id)
        assertEquals(detail, item.detail)
    }

    @Test fun inheritedHeldWorkAndItsBoundariesLeadTheFirstPage() {
        val items = List(30) { PlanItem("other-$it", "Other $it", detail = "Unrelated detail ".repeat(300)) } + listOf(
            PlanItem("area", "Assigned area", kind = PlanKind.Component, refs = listOf(PlanPeople.ref(agent))),
            PlanItem("before", "Prerequisite", status = PlanStatus.Done),
            PlanItem("held", "Held work", parent = "area", after = listOf("before"), status = PlanStatus.Doing),
            PlanItem("later", "Dependent", after = listOf("held")))
        val plan = WorkPlan("hangar:bounded", "Scoped", items = items, createdBy = person, createdAt = 1, updatedAt = 1)
        val first = PlanReadPage.answer(plan, agent, budgetBytes = 8192)
        val ids = first.getValue("plan").jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        assertTrue(ids.containsAll(listOf("area", "held", "before", "later")))
        assertEquals(false, first.getValue("complete").jsonPrimitive.boolean)
        assertTrue("continuation" in first)
    }

    @Test fun nextWithoutAnExplicitThreadKeepsItsPageAndConfiguredByteBudget() = fixture { workspace ->
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Bounded", ops = List(40) {
            PlanOp.Add("Task $it", key = "p$it", detail = "évidence ".repeat(600)) }), person)
        val tools = agentPlanTools(workspace)
        tools.single { it.name == "agent_plan_write" }.invoke(buildJsonObject {
            put("thread", "hangar:bounded"); put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), person))
            put("base", workspace.plans.plan("hangar:bounded")!!.revision)
            put("limits", buildJsonObject { put("doingPerHolder", 5); put("readBytes", 8192); put("maxReadBytes", 262144) })
        })
        val next = tools.single { it.name == "agent_plan_next" }
        val who = buildJsonObject { put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), agent)) }
        val first = next.invoke(who).jsonObject
        assertTrue(first.toString().encodeToByteArray().size <= 8192)
        assertEquals("agent-page", first.getValue("projection").jsonPrimitive.content)
        assertEquals(true, first.getValue("candidate").jsonObject.getValue("itemPartial").jsonPrimitive.boolean)
        val cursor = first.getValue("continuation").jsonPrimitive.content
        val second = next.invoke(JsonObject(who + ("continuation" to JsonPrimitive(cursor)))).jsonObject
        assertTrue(second.toString().encodeToByteArray().size <= 8192)
        assertTrue(second.getValue("offset").jsonPrimitive.int > first.getValue("offset").jsonPrimitive.int)
        val maximum = tools.single { it.name == "agent_plan_read" }.invoke(args(buildJsonObject { put("budgetBytes", 1000000) })).jsonObject
        assertTrue(maximum.toString().encodeToByteArray().size <= 262144)
    }

    @Test fun anAgentsReadDoesNotOfferReadyWorkWhoseFilesAreHeldByAnotherWriter() = fixture { workspace ->
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Scope", ops = listOf(
            PlanOp.Add("Busy", key = "busy", status = PlanStatus.Doing, refs = listOf("file:Shared.kt")),
            PlanOp.Add("Waiting for files", key = "free", refs = listOf("file:Shared.kt")))), person)
        val reply = agentPlanTools(workspace).single { it.name == "agent_plan_read" }.invoke(args()).jsonObject
        assertTrue(reply.getValue("ready").jsonArray.isEmpty())
    }

    @Test fun anAgentWithoutABoundSessionStillReadsAnExplicitThreadWithoutAnOffer() = fixture { workspace ->
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Read only", ops = listOf(PlanOp.Add("Visible", key = "visible"))), person)
        val reply = agentPlanTools(workspace).single { it.name == "agent_plan_read" }.invoke(args(buildJsonObject {
            put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), agent.copy(session = null)))
        })).jsonObject
        assertEquals("visible", reply.getValue("plan").jsonObject.getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(reply.getValue("ready").jsonArray.isEmpty())
        assertTrue(reply.toString().encodeToByteArray().size <= 65_536)
    }

    @Test fun aBoundProfilesAssignedWorkLeadsReadAndNextPages() = fixture { workspace ->
        val profile = workspace.profiles.save(AgentProfile("bounded-worker", "Bounded worker", RuntimeKind.Codex), person)
        workspace.bindIdentity(agent, profile.id, workspace.identity(agent).bindingRevision)
        val canonical = workspace.planIdentity(agent)
        assertTrue(canonical.name != agent.name)
        workspace.plans.write("hangar:bounded", PlanPatch(title = "Assigned", ops =
            List(30) { PlanOp.Add("Other $it", key = "other-$it", detail = "Unrelated detail ".repeat(300)) } + listOf(
                PlanOp.Add("Assigned frame", key = "area", kind = PlanKind.Component, refs = listOf(PlanPeople.ref(canonical))),
                PlanOp.Add("Held", key = "held", parent = "area", status = PlanStatus.Doing),
                PlanOp.Add("Ready", key = "ready", parent = "area", refs = listOf("file:Ready.kt"))
            )), person)
        for (name in listOf("agent_plan_read", "agent_plan_next")) {
            val answer = agentPlanTools(workspace).single { it.name == name }.invoke(args(buildJsonObject { put("budgetBytes", 8192) })).jsonObject
            val ids = answer.getValue("plan").jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            assertTrue(ids.containsAll(listOf("area", "held", "ready")), "$name must lead with the bound agent's assigned work")
        }
    }

}
