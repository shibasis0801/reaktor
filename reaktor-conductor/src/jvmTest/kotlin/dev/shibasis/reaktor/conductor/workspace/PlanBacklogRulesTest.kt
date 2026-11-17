package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanBacklogRulesTest {
    private val owner = BoardActor(BoardActorKind.Person, "Fixture owner", principal = "fixture-owner")
    private fun empty(thread: String = "hangar:backlog-rules") = WorkPlan(thread, "Backlog", createdBy = owner, createdAt = 1, updatedAt = 1)
    private fun configured(limit: Int): WorkPlan {
        val source = ConductorJson.encodeToJsonElement(WorkPlan.serializer(), empty()).jsonObject
        return ConductorJson.decodeFromJsonElement(WorkPlan.serializer(), JsonObject(source + ("limits" to buildJsonObject { put("doingPerHolder", limit) })))
    }
    private fun apply(plan: WorkPlan, vararg ops: PlanOp, actor: BoardActor = owner) =
        PlanRules.apply(plan, PlanPatch(ops.toList(), base = plan.revision), actor, 100 + plan.revision)

    @Test fun sixteenHundredOpenItemsAreAcceptedInLegalBatches() {
        var plan = empty()
        repeat(16) { batch ->
            val answer = apply(plan, *Array(100) { PlanOp.Add("Open item", key = "p${batch * 100 + it}") })
            assertTrue(answer.refused.isEmpty(), "The backlog must not consume a Doing slot")
            plan = answer.plan
        }
        assertEquals(1600, plan.items.size)
    }

    @Test fun aPersonMayHoldFiveDoingLeavesAndTheSixthIsRefusedPerOperation() {
        val answer = apply(empty(), *Array(6) { PlanOp.Add("Active item", key = "p$it", status = PlanStatus.Doing) })
        assertEquals(5, answer.plan.items.size)
        assertEquals(5, answer.refused.single().op)
        assertTrue(answer.refused.single().reason.contains("at most 5 Doing leaves"))
        val freed = apply(answer.plan, PlanOp.Status("p0", PlanStatus.Todo), PlanOp.Add("Replacement", key = "replacement", status = PlanStatus.Doing))
        assertTrue(freed.refused.isEmpty())
    }

    @Test fun eachPlanConfiguresItsOwnLimitAndComponentsConsumeNoSlots() {
        val answer = apply(configured(2), PlanOp.Add("Frame", key = "frame", kind = PlanKind.Component, status = PlanStatus.Doing),
            *Array(3) { PlanOp.Add("Active item", key = "p$it", parent = "frame", status = PlanStatus.Doing) })
        assertEquals(listOf("frame", "p0", "p1"), answer.plan.items.map { it.id })
        assertEquals(3, answer.refused.single().op)
        assertTrue(answer.refused.single().reason.contains("at most 2 Doing leaves"))
        assertTrue(apply(empty("hangar:second-backlog"), *Array(5) { PlanOp.Add("Separate plan", key = "p$it", status = PlanStatus.Doing) }).refused.isEmpty())
    }

    @Test fun separateAssignedAgentsHaveSeparateSlotsAndARefCannotBypassTheLimit() {
        val first = apply(empty(), *Array(5) { PlanOp.Add("First lane", key = "a$it", refs = listOf("agent:alpha"), status = PlanStatus.Doing) })
        assertTrue(first.refused.isEmpty())
        val second = apply(first.plan, *Array(5) { PlanOp.Add("Second lane", key = "b$it", refs = listOf("agent:beta"), status = PlanStatus.Doing) })
        assertTrue(second.refused.isEmpty())
        val moved = apply(second.plan, PlanOp.Update("b0", refs = listOf("agent:alpha")))
        assertEquals(0, moved.refused.single().op)
        assertEquals(listOf("agent:beta"), moved.plan.item("b0")!!.refs)
    }

    @Test fun unrelatedHangarSessionsDoNotShareASlotThroughTheirDisplayName() {
        val first = BoardActor(BoardActorKind.Agent, "Fixture runtime", "hangar:first-writer")
        val second = BoardActor(BoardActorKind.Agent, "Fixture runtime", "hangar:second-writer")
        val held = empty().copy(items = List(5) { PlanItem("p$it", "Held", status = PlanStatus.Doing,
            by = first, lease = PlanLease(first, 1, 10000)) })
        val answer = apply(held, *Array(5) { PlanOp.Add("Independent work", key = "q$it", status = PlanStatus.Doing) }, actor = second)
        assertTrue(answer.refused.isEmpty())
        assertEquals(10, answer.plan.items.size)
    }

    @Test fun loweringALimitRequiresACompliantFinalPlanAndARevisionCheckedOwner() {
        val held = apply(empty(), *Array(3) { PlanOp.Add("Active", key = "p$it", status = PlanStatus.Doing) }).plan
        fun configuredPatch(limit: Int, base: Long, ops: List<PlanOp> = emptyList()): PlanPatch {
            val source = ConductorJson.encodeToJsonElement(PlanPatch.serializer(), PlanPatch(base = base, ops = ops)).jsonObject
            return ConductorJson.decodeFromJsonElement(PlanPatch.serializer(), JsonObject(source + ("limits" to buildJsonObject { put("doingPerHolder", limit) })))
        }
        assertFailsWith<IllegalArgumentException> { PlanRules.apply(held, configuredPatch(2, held.revision), owner, 200) }
        assertFailsWith<IllegalArgumentException> { PlanRules.apply(held, configuredPatch(4, held.revision - 1), owner, 200) }
        val visitor = BoardActor(BoardActorKind.Agent, "Visitor", "codex:visitor")
        assertFailsWith<IllegalArgumentException> { PlanRules.apply(held, configuredPatch(4, held.revision), visitor, 200) }
        val closed = PlanRules.apply(held, configuredPatch(2, held.revision, listOf(PlanOp.Status("p0", PlanStatus.Todo))), owner, 200)
        assertTrue(closed.refused.isEmpty())
        val encoded = ConductorJson.encodeToJsonElement(WorkPlan.serializer(), closed.plan).jsonObject
        assertEquals("2", encoded.getValue("limits").jsonObject.getValue("doingPerHolder").toString())
    }

    @Test fun assignedFramesGiveTheirLeavesTheNamedHoldersLimit() {
        val frames = apply(empty(), PlanOp.Add("Alpha", key = "alpha", kind = PlanKind.Component, refs = listOf("agent:alpha")),
            PlanOp.Add("Beta", key = "beta", kind = PlanKind.Component, refs = listOf("agent:beta"))).plan
        val first = apply(frames, *Array(5) { PlanOp.Add("Alpha work", key = "a$it", parent = "alpha", status = PlanStatus.Doing) })
        val second = apply(first.plan, *Array(5) { PlanOp.Add("Beta work", key = "b$it", parent = "beta", status = PlanStatus.Doing) })
        assertTrue(first.refused.isEmpty())
        assertTrue(second.refused.isEmpty())
        assertEquals(0, apply(second.plan, PlanOp.Add("Overflow", key = "overflow", parent = "alpha", status = PlanStatus.Doing)).refused.single().op)
    }

    @Test fun aLeaseUnderANamedFrameAlsoConsumesThatNamesSlots() {
        val actor = BoardActor(BoardActorKind.Agent, "(codex)", "codex:alpha")
        val frame = PlanItem("frame", "Assigned area", kind = PlanKind.Component, refs = listOf("agent:alpha"))
        val items = listOf(frame) + List(5) { PlanItem("p$it", "Held", parent = "frame", status = PlanStatus.Doing, by = actor,
            lease = PlanLease(actor, 1, 10000)) }
        val answer = apply(empty().copy(items = items), PlanOp.Add("Overflow", key = "overflow", status = PlanStatus.Doing, refs = listOf("agent:alpha")))
        assertEquals(0, answer.refused.single().op)
        assertEquals(6, answer.plan.items.size)
    }

    @Test fun aLeaseAndItsNamedRefConsumeOneHoldersSlots() {
        val actor = BoardActor(BoardActorKind.Agent, "(codex)", "codex:alpha")
        val paired = PlanItem("paired", "Paired", status = PlanStatus.Doing, refs = listOf("agent:alpha"),
            by = actor, lease = PlanLease(actor, 1, 10000))
        val items = listOf(paired) + List(4) { PlanItem("p$it", "Held", status = PlanStatus.Doing, by = actor,
            lease = PlanLease(actor, 1, 10000)) }
        val answer = apply(empty().copy(items = items), PlanOp.Add("Overflow", key = "overflow", status = PlanStatus.Doing, refs = listOf("agent:alpha")))
        assertEquals(0, answer.refused.single().op)
        assertEquals(5, answer.plan.items.size)
    }
    @Test fun attachingADoingRootCannotMoveASixthLeafOntoThePerson() {
        val other = BoardActor(BoardActorKind.Person, "Other owner", principal = "other-owner")
        val plan = empty().copy(items = List(5) { PlanItem("p$it", "Held", status = PlanStatus.Doing, by = owner) } +
            PlanItem("other", "Other work", status = PlanStatus.Doing, by = other))
        val attached = PlanRules.attach(plan, PlanSeat("seat", "fixture", listOf("other"), owner, 10), 10)
        assertEquals("seat", attached.item("other")!!.seat)
        assertEquals(other, attached.item("other")!!.by)
        assertEquals(owner, attached.seats.single().by)
        assertEquals(other, plan.item("other")!!.by)
    }

    @Test fun aReviewerDoesNotConsumeTheAssigneesDoingSlot() {
        val plan = empty().copy(items = List(5) { PlanItem("p$it", "Implementer work", status = PlanStatus.Doing,
            refs = listOf("agent:codex-x", "agent:claude-y")) })
        val answer = apply(plan, PlanOp.Add("Review", key = "review", status = PlanStatus.Doing, refs = listOf("agent:claude-y")))
        assertTrue(answer.refused.isEmpty(), "A reviewer ref must not consume a Doing slot")
        val refused = apply(answer.plan, PlanOp.Add("Sixth implementation", key = "sixth", status = PlanStatus.Doing,
            refs = listOf("agent:codex-x", "agent:claude-y")))
        assertEquals(0, refused.refused.single().op)
    }

    @Test fun aDoingLimitRefusalNamesItsHolderAndExistingLeaves() {
        val answer = apply(empty(), *Array(6) { PlanOp.Add("Active item", key = "p$it", status = PlanStatus.Doing) })
        val reason = answer.refused.single().reason
        assertTrue(reason.contains("Fixture owner"))
        assertTrue((0..4).all { reason.contains("p$it") })
        assertTrue(reason.contains("finish or release one with plan.write before starting another item"))
    }

    @Test fun anExplicitIdentityWinsOverTheFirstRefForDoingSlots() {
        val bound = BoardActor(BoardActorKind.Agent, "bound-agent", "codex:bound", profile = "bound-agent")
        val items = List(5) { PlanItem("p$it", "Held", status = PlanStatus.Doing,
            refs = listOf("agent:old-assignee", "agent:reviewer"), by = bound, lease = PlanLease(bound, 1, 10000)) }
        val answer = apply(empty().copy(items = items), PlanOp.Add("Overflow", key = "overflow", status = PlanStatus.Doing,
            refs = listOf("agent:bound-agent")))
        assertEquals(0, answer.refused.single().op)
    }

}
