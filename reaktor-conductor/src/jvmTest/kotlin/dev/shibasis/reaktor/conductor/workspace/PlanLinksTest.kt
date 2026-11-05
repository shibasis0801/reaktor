package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlanLinksTest {
    private fun actor(session: String) = BoardActor(BoardActorKind.Agent, "Fixture agent", session)
    private val planner = actor("claude:fixture-planner")
    private fun plan(thread: String, by: BoardActor = planner, at: Long = 1) =
        WorkPlan(thread, "Fixture plan", createdBy = by, createdAt = at, updatedAt = at)
    private fun claim(session: String, at: Long) = PlanItem("claim-$session", "Fixture claim", status = PlanStatus.Doing,
        changedAt = at, lease = PlanLease(actor(session), at, at + PlanLease.DurationMillis))

    @Test fun sevenNativeSessionsLinkThroughCreationChangesClaimsAndSeats() {
        val sessions = listOf("claude:fixture-planner", "claude:fixture-architect-a", "claude:fixture-architect-b") +
            (1..4).map { "codex:fixture-engineer-$it" }
        val team = plan(sessions.first()).copy(
            items = listOf(claim(sessions[2], 3), claim(sessions[3], 4), claim(sessions[4], 5)),
            seats = listOf(PlanSeat("seat", "fixture", listOf("work"), planner, 6, session = sessions[5])),
            changes = listOf(PlanChange(2, 2, actor(sessions[1]), listOf("Status changed")),
                PlanChange(3, 7, actor(sessions[6]), listOf("Item updated"))))
        sessions.forEach { assertSame(team, linkedPlan(it, listOf(team))) }
    }

    @Test fun anOpenClaimWinsOverASeatAndALaterWrite() {
        val session = "codex:fixture-agent"
        val held = plan("claude:held").copy(items = listOf(claim(session, 2)))
        val later = plan("claude:later", actor(session), 100).copy(
            seats = listOf(PlanSeat("seat", "fixture", emptyList(), planner, 100, session = session)))
        assertSame(held, linkedPlan(session, listOf(later, held)))
    }

    @Test fun newestClaimWinsAndClosedItemsDoNotLink() {
        val session = "codex:fixture-agent"
        val first = plan("claude:first").copy(items = listOf(claim(session, 2)))
        val latest = plan("claude:latest").copy(items = listOf(claim(session, 3)))
        assertSame(latest, linkedPlan(session, listOf(first, latest)))
        assertNull(linkedPlan(session, listOf(latest.copy(items = latest.items.map { it.copy(status = PlanStatus.Done) }))))
    }

    @Test fun aRetiredSeatDoesNotLinkButAnActiveSeatWinsOverALaterWrite() {
        val session = "codex:fixture-agent"
        val seat = PlanSeat("seat", "fixture", emptyList(), planner, 2, session = session)
        val seated = plan("claude:seated").copy(seats = listOf(seat))
        assertNull(linkedPlan(session, listOf(seated.copy(seats = listOf(seat.copy(retired = PlanSeatRetirement(planner, 3, "Fixture retirement")))))))
        assertSame(seated, linkedPlan(session, listOf(plan("codex:written", actor(session), 100), seated)))
    }

    @Test fun creationAndLatestChangeCountAsWritesAndAnUninvolvedSessionHasNoPlan() {
        val session = "codex:fixture-own"
        val own = plan(session, actor(session), 10)
        assertSame(own, linkedPlan(session, listOf(plan("claude:other"), own)))
        val changed = plan("claude:changed").copy(changes = listOf(PlanChange(2, 20, actor(session), listOf("Item updated"))))
        assertSame(changed, linkedPlan(session, listOf(own, changed)))
        assertNull(linkedPlan("claude:fixture-loose", listOf(own, changed)))
    }
}
