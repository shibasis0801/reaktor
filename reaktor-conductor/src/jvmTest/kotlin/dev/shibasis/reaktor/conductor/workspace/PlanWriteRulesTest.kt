package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class PlanWriteRulesTest {
    private val thread = "hangar:rules"
    private val person = BoardActor(BoardActorKind.Person, "Shibasis")
    private val holder = BoardActor(BoardActorKind.Agent, "Codex", "codex:holder", "agent:holder")
    private val other = BoardActor(BoardActorKind.Agent, "Claude", "claude:other", "agent:other")
    private val sessionless = BoardActor(BoardActorKind.Agent, "claude-code")
    private val threadAgent = BoardActor(BoardActorKind.Agent, "Lead", thread)

    private fun plan(vararg items: PlanItem, seats: List<PlanSeat> = emptyList()) =
        WorkPlan(thread, "Rules", items = items.toList(), seats = seats, revision = 1, createdBy = person, createdAt = 0, updatedAt = 0)

    private fun apply(plan: WorkPlan, by: BoardActor, vararg ops: PlanOp, seat: String? = null) =
        PlanRules.apply(plan, PlanPatch(ops = ops.toList(), seat = seat), by, 1_000)

    private fun seated() = plan(PlanItem("area", "Area", seat = "s1"), PlanItem("part", "Part", parent = "area"),
        seats = listOf(PlanSeat("s1", "codex", listOf("area"), person, 0, session = holder.session, agent = holder.agent)))

    @Test fun aSessionlessAgentCannotWriteThroughAnotherAgentsSeat() {
        val refused = assertFailsWith<IllegalArgumentException> { apply(seated(), sessionless, PlanOp.Status("part", PlanStatus.Doing), seat = "s1") }
        assertContains(refused.message.orEmpty(), "s1 belongs to codex:holder")
        assertFailsWith<IllegalArgumentException> { apply(seated(), other, PlanOp.Status("part", PlanStatus.Doing), seat = "s1") }
        assertContains(apply(seated(), sessionless, PlanOp.Status("part", PlanStatus.Doing)).refused.single().reason, "belongs to seat s1")
    }

    @Test fun aPersonAndTheSeatHolderStillWriteThroughTheSeat() {
        listOf(person, holder).forEach { caller ->
            val applied = apply(seated(), caller, PlanOp.Status("part", PlanStatus.Doing), seat = "s1")
            assertTrue(applied.refused.isEmpty())
            assertEquals(PlanStatus.Doing, applied.plan.item("part")?.status)
            assertEquals(caller, applied.plan.item("part")?.by)
        }
    }

    @Test fun anUnboundSeatIsStillClaimedByTheFirstSessionThatUsesIt() {
        val open = plan(PlanItem("area", "Area", seat = "s1"), seats = listOf(PlanSeat("s1", "codex", listOf("area"), person, 0)))
        val applied = apply(open, other, PlanOp.Update("area", detail = "Mine now"), seat = "s1")
        assertTrue(applied.refused.isEmpty())
        assertEquals(other.session, applied.plan.seat("s1")?.session)
    }

    private fun leased() = plan(PlanItem("build", "Build", status = PlanStatus.Doing, by = holder, lease = PlanLease(holder, 0, PlanLease.DurationMillis)))

    @Test fun anEditToALeasedItemRecordsTheRealWriterAndKeepsTheLease() {
        listOf(person, threadAgent).forEach { writer ->
            val edited = apply(leased(), writer, PlanOp.Update("build", detail = "Narrowed")).plan.item("build")
            assertEquals(writer, edited?.by)
            assertEquals(holder, edited?.lease?.by)
            assertEquals("Narrowed", edited?.detail)
        }
    }

    @Test fun theLeaseHolderStillGuardsItsItemAfterSomeoneElseEditsIt() {
        val edited = apply(leased(), person, PlanOp.Update("build", detail = "Narrowed")).plan
        assertContains(apply(edited, other, PlanOp.Status("build", PlanStatus.Done)).refused.single().reason, "in progress by Codex")
        assertTrue(apply(edited, holder, PlanOp.Status("build", PlanStatus.Done)).refused.isEmpty())
    }

    @Test fun waitingOnSomethingThatInheritsYourOwnWaitIsACycle() {
        val inherited = plan(PlanItem("lane", "Lane", kind = PlanKind.Component, after = listOf("gate")), PlanItem("step", "Step", parent = "lane"), PlanItem("gate", "Gate"))
        val refused = apply(inherited, person, PlanOp.Connect("step", "gate"))
        assertContains(refused.refused.single().reason, "gate waiting on step would make a cycle")
        assertEquals(inherited, refused.plan)
    }

    @Test fun waitingOnYourOwnComponentIsACycle() {
        val nested = plan(PlanItem("lane", "Lane", kind = PlanKind.Component), PlanItem("step", "Step", parent = "lane"))
        assertContains(apply(nested, person, PlanOp.Connect("lane", "step")).refused.single().reason, "would make a cycle")
        assertContains(apply(nested, person, PlanOp.Connect("step", "step")).refused.single().reason, "would make a cycle")
    }

    @Test fun addingOrMovingAnItemUnderAComponentCannotCloseACycle() {
        val waiting = plan(PlanItem("lane", "Lane", kind = PlanKind.Component), PlanItem("review", "Review", after = listOf("lane")),
            PlanItem("loose", "Loose", after = listOf("review")))
        assertContains(apply(waiting, person, PlanOp.Add("Fix", key = "fix", parent = "lane", after = listOf("review"))).refused.single().reason, "would make a cycle")
        val moved = apply(waiting, person, PlanOp.Update("loose", parent = "lane"))
        assertContains(moved.refused.single().reason, "loose under lane would wait on itself")
        assertEquals(waiting, moved.plan)
    }

    @Test fun siblingsAndInheritedWaitsWithoutALoopAreAccepted() {
        val lanes = plan(PlanItem("gate", "Gate"), PlanItem("lane", "Lane", kind = PlanKind.Component, after = listOf("gate")),
            PlanItem("first", "First", parent = "lane"), PlanItem("second", "Second", parent = "lane"), PlanItem("after", "After"))
        val applied = apply(lanes, person, PlanOp.Connect("first", "second"), PlanOp.Connect("lane", "after"),
            PlanOp.Add("Third", key = "third", parent = "lane", after = listOf("second")), PlanOp.Connect("gate", "after"))
        assertTrue(applied.refused.isEmpty(), applied.refused.toString())
        assertEquals(listOf("first"), applied.plan.item("second")?.after)
        assertEquals(listOf("second"), applied.plan.item("third")?.after)
    }
}
