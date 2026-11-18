package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanApprovalChangeTest {
    @Test fun anAgentChangeKeepsApprovalAndNamesTheRevisionUntilAPersonApprovesAgain() {
        val owner = BoardActor(BoardActorKind.Person, "Owner")
        val agent = BoardActor(BoardActorKind.Agent, "Builder", "codex:builder")
        val source = WorkPlan("hangar:approval", "Approval", items = listOf(PlanItem("task", "Task")),
            createdBy = owner, createdAt = 1, updatedAt = 1)
        val approved = PlanRules.apply(source, PlanPatch(base = 0, approval = PlanApproval.Approved), owner, 2).plan
        val moved = PlanRules.apply(approved, PlanPatch(base = approved.revision,
            ops = listOf(PlanOp.Update("task", title = "Changed task"))), agent, 3)
        assertTrue(moved.refused.isEmpty())
        assertEquals(PlanApproval.Approved, moved.plan.approval)
        fun changed(plan: WorkPlan) = ConductorJson.encodeToJsonElement(WorkPlan.serializer(), plan).jsonObject["approvalChangedRevision"]
        assertEquals(JsonPrimitive(moved.plan.revision), changed(moved.plan))
        val personChange = PlanRules.apply(moved.plan, PlanPatch(base = moved.plan.revision,
            ops = listOf(PlanOp.Update("task", title = "Owner wording"))), owner, 4).plan
        assertEquals(changed(moved.plan), changed(personChange))
        val confirmed = PlanRules.apply(personChange, PlanPatch(base = personChange.revision,
            approval = PlanApproval.Approved), owner, 5).plan
        assertTrue(changed(confirmed) == null || changed(confirmed) == JsonNull)
        assertEquals(confirmed.revision, confirmed.approvalRevision)
    }
}
