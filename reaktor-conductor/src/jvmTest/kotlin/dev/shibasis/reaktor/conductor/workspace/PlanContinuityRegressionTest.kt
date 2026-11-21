package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.json.*
import kotlin.test.*

class PlanContinuityRegressionTest {
    private val person = BoardActor(BoardActorKind.Person, "Owner")
    private val agent = BoardActor(BoardActorKind.Agent, "Worker", "codex:continuity")
    private fun plan() = WorkPlan("hangar:continuity", "Continuity", items =
        listOf(PlanItem("frame", "Frame", kind = PlanKind.Component)) + List(24) {
            PlanItem("p$it", "Task $it", parent = "frame", detail = "evidence ".repeat(500), revision = 230)
        }, createdBy = person, createdAt = 1, updatedAt = 230, revision = 230,
        changes = (31L..230L).map { PlanChange(it, it, person, listOf("Says: change $it")) })

    @Test fun newsOlderThanTheRetainedWindowNamesTheGap() {
        val p = plan()
        val page = PlanReadPage.answer(p, agent, buildJsonObject {
            put("news", AgentWorkspaceJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PlanChange.serializer()), p.news(1, agent)))
            put("since", 1)
        }, budgetBytes = 8192, scoped = true)
        assertEquals(true, page["newsGap"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(31, page["newsFrom"]?.jsonPrimitive?.int)
        assertEquals(true, page["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals((0 until 24).map { "p$it" }.toSet(), page["changedItems"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet())
    }

    @Test fun aContinuationKeepsItsFrameWhenTheCallerOmitsIt() {
        val p = plan()
        val first = PlanReadPage.answer(p, agent, frame = "frame", budgetBytes = 8192)
        val token = first.getValue("continuation").jsonPrimitive.content
        val next = PlanReadPage.answer(p, agent, continuation = token, budgetBytes = 8192)
        assertNotEquals(true, next["restart"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(next.getValue("offset").jsonPrimitive.int > 0)
    }

    @Test fun onlyTheLastItemPageAdvancesTheNewsRevision() {
        val p = plan()
        val first = PlanReadPage.answer(p, agent, budgetBytes = 8192)
        assertNotNull(first["continuation"])
        assertNull(first["nextSince"])
        var page = first
        var count = 0
        while (page["continuation"] != null) {
            page = PlanReadPage.answer(p, agent, continuation = page.getValue("continuation").jsonPrimitive.content, budgetBytes = 8192)
            assertTrue(++count < 100)
        }
        assertEquals(p.revision, page["nextSince"]?.jsonPrimitive?.long)
    }

    @Test fun onePatchCannotHideNewDocumentTextBehindANewFileLink() {
        val p = plan()
        val result = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, documentPath = "plans/new.md", documentText = "Hidden text"), person, 300)
        assertNull(result.plan.documentText)
        assertContains(result.refused.single().reason, "Unlink")
    }

    @Test fun aLinkedDocumentCannotBeSilentlyShadowedBySharedText() {
        val p = plan().copy(documentPath = "plans/work.md")
        val result = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, documentText = "Invisible text"), person, 300)
        assertNull(result.plan.documentText)
        assertContains(result.refused.single().reason, "Unlink")
    }

    @Test fun approvalNamesTheRevisionItCoversAndBecomesOutOfDateAfterAnEdit() {
        val p = plan()
        val approved = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, approval = PlanApproval.Approved), person, 300).plan
        val value = AgentWorkspaceJson.encodeToJsonElement(WorkPlan.serializer(), approved).jsonObject
        assertEquals(approved.revision, value["approvalRevision"]?.jsonPrimitive?.long)
        val moved = PlanRules.apply(approved, PlanPatch(listOf(PlanOp.Update("p0", title = "Changed")), base = approved.revision), person, 301).plan
        assertEquals(approved.revision, AgentWorkspaceJson.encodeToJsonElement(WorkPlan.serializer(), moved).jsonObject["approvalRevision"]?.jsonPrimitive?.long)
    }

    @Test fun anAgentCannotApproveAndAThirdPartyAgentCannotChangeTheDocument() {
        val p = plan()
        val approval = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, approval = PlanApproval.Approved), agent, 300)
        assertEquals("Only a person changes the plan's approval state", approval.refused.single().reason)
        val doc = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, documentText = "Not mine"), agent, 300)
        assertEquals("Only the plan's owner can change its document", doc.refused.single().reason)
        val big = PlanRules.apply(p, PlanPatch(emptyList(), base = p.revision, documentText = "é".repeat(262145)), person, 300)
        assertEquals("The plan document is at most 512 KB of UTF-8", big.refused.single().reason)
    }
    @Test fun aPartialLimitsPatchMergesAgainstThePlanItIsAppliedTo() {
        val p = plan().copy(limits = PlanLimits(8, 8192, 32768))
        val patch = AgentWorkspaceJson.decodeFromString(PlanPatch.serializer(),
            """{"base":230,"limits":{"readBytes":16384},"limitFields":["readBytes"]}""")
        val applied = PlanRules.apply(p, patch, person, 300)
        assertTrue(applied.refused.isEmpty())
        assertEquals(PlanLimits(8, 16384, 32768), applied.plan.limits)
    }

}
