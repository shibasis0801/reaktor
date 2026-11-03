package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentPolicyTest {
    private val now = 10_000_000L
    private val engine = BoardActor(BoardActorKind.Agent, "(codex, xhigh, engine)", "codex:a", "agent:codex:a", "bpo-engine", 1)
    private val auditor = BoardActor(BoardActorKind.Agent, "(codex, xhigh, auditor)", "codex:c", "agent:codex:c", "bpo-auditor", 2)
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val lease = PlanLease(engine, now - 5_000, now + 10_000)
    private val audit = PlanLease(auditor, now - 5_000, now + 10_000)

    private val plan = WorkPlan("hangar:assign", "Assign", items = listOf(
        PlanItem("free", "Nobody has it", by = owner),
        PlanItem("seated", "A person gave it to the designer", by = owner, seat = "designer-1"),
        PlanItem("routes", "Routes", kind = PlanKind.Component, by = owner, seat = "engine-1"),
        PlanItem("doors", "Doors under routes", parent = "routes", by = owner),
        PlanItem("claimed", "The engine took it", status = PlanStatus.Doing, by = engine, lease = lease),
        PlanItem("both", "Seated for the designer, claimed by the auditor", status = PlanStatus.Doing, by = auditor, lease = audit, seat = "designer-1"),
        PlanItem("retired", "Its seat was retired", by = owner, seat = "old-1"),
        PlanItem("done", "Finished", status = PlanStatus.Done, by = engine),
        PlanItem("dropped", "Won't do", status = PlanStatus.Dropped, by = owner, seat = "designer-1"),
    ), seats = listOf(
        PlanSeat("designer-1", "bpo-designer", listOf("seated", "both", "dropped"), owner, now - 60_000, agent = "agent:codex:d"),
        PlanSeat("engine-1", "bpo-engine", listOf("routes"), owner, now - 50_000),
        PlanSeat("old-1", "bpo-cartographer", listOf("retired"), owner, now - 90_000, retired = PlanSeatRetirement(owner, now - 80_000, "Moved on")),
    ), createdBy = owner, createdAt = 0, updatedAt = now)

    private val presence = AgentPresencePage(listOf(engine, auditor).map { actor ->
        AgentPresence(actor, RuntimeKind.Codex, live = true, connected = true, lastSeen = now - 1_000, lastAction = "plan.write", lastActionAt = now - 1_000)
    }, now)

    @Test fun aPersonsSeatComesFirstThenAClaimAndOnlyOpenItemsAreAssigned() {
        val claims = mapOf(plan.thread to PlanClaims.resolve(plan, presence, now))
        assertEquals(listOf(
            Assignment(plan.thread, "seated", "bpo-designer", "agent:codex:d", AssignmentSource.Seat),
            Assignment(plan.thread, "routes", "bpo-engine", null, AssignmentSource.Seat),
            Assignment(plan.thread, "doors", "bpo-engine", null, AssignmentSource.Seat),
            Assignment(plan.thread, "claimed", "bpo-engine", "agent:codex:a", AssignmentSource.Claim),
            Assignment(plan.thread, "both", "bpo-designer", "agent:codex:d", AssignmentSource.Seat),
        ), AssignmentPolicy.Explicit.assign(AgentTeam(), listOf(plan), claims))
    }

    @Test fun withoutClaimsOnlySeatsAssignAndAnotherPolicyCanTakeItsPlace() {
        assertEquals(listOf("seated", "routes", "doors", "both"), AssignmentPolicy.Explicit.assign(AgentTeam(), listOf(plan), emptyMap()).map { it.item })
        val everyoneToTheLead = AssignmentPolicy { _, plans, _ ->
            plans.flatMap { plan -> plan.items.map { Assignment(plan.thread, it.id, "bpo-lead", null, AssignmentSource.Seat) } }
        }
        assertEquals(plan.items.size, everyoneToTheLead.assign(AgentTeam(), listOf(plan), emptyMap()).size)
    }
}
