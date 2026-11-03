package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanClaimsTest {
    private val now = 10_000_000L
    private val a = BoardActor(BoardActorKind.Agent, "(codex, xhigh, engine)", "codex:a", "agent:codex:a", "bpo-engine", 1)
    private val b = BoardActor(BoardActorKind.Agent, "(claude, max, agents lead)", "claude:b", "agent:claude:b", "bpo-agents-lead", 4)
    private val c = BoardActor(BoardActorKind.Agent, "(codex, xhigh, auditor)", "codex:c", "agent:codex:c", "bpo-auditor", 2)
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val held = PlanLease(a, now - 5_000, now + 10_000)
    private val expired = PlanLease(c, now - PlanLease.DurationMillis * 2, now - PlanLease.DurationMillis)

    private val plan = WorkPlan("hangar:claims", "Claims", items = listOf(
        PlanItem("a", "Unassigned", by = owner),
        PlanItem("b", "Held by a", status = PlanStatus.Doing, by = a, lease = held),
        PlanItem("d", "Quiet lease", status = PlanStatus.Doing, by = c, lease = expired),
        PlanItem("e", "Closed with history", status = PlanStatus.Done, by = owner, changedAt = now - 40_000),
        PlanItem("f", "Closed before the history", status = PlanStatus.Done, by = c, changedAt = now - 90_000),
    ), changes = listOf(PlanChange(7, now - 50_000, b, listOf("e → Done"))), createdBy = owner, createdAt = 0, updatedAt = now)

    private fun presence(live: Boolean) = AgentPresencePage(listOf(AgentPresence(a, RuntimeKind.Codex, live = live, connected = false,
        lastSeen = now - 1_000, lastAction = "plan.write", lastActionAt = now - 2_000)), now)

    @Test fun eachItemGetsOneAnswerFromLeasesPresenceAndHistory() {
        val claims = PlanClaims.resolve(plan, presence(live = true), now)
        assertEquals(PlanClaim(ClaimState.Unassigned), claims.of(plan.item("a")!!))
        assertEquals(PlanClaim(ClaimState.Live, a, held.takenAt, now - 2_000), claims.of(plan.item("b")!!))
        assertEquals(PlanClaim(ClaimState.Stale, c, expired.expiresAt), claims.of(plan.item("d")!!))
        assertEquals(PlanClaim(ClaimState.Completed, b, now - 50_000, profileRevision = 4), claims.of(plan.item("e")!!))
        assertEquals(PlanClaim(ClaimState.LastChangedBy, c, now - 90_000, profileRevision = 2), claims.of(plan.item("f")!!))
        assertEquals(now - 1_000 + PlanClaims.FreshMillis, claims.nextChangeAt)
    }

    @Test fun aHolderWhoIsNotLiveStillHoldsAndUnreadablePresenceSaysSo() {
        assertEquals(PlanClaim(ClaimState.Claimed, a, held.takenAt), PlanClaims.resolve(plan, presence(live = false), now).of(plan.item("b")!!))
        assertEquals(now - 1_000 + PlanLease.DurationMillis, PlanClaims.resolve(plan, presence(live = false), now).nextChangeAt)
        assertEquals(PlanClaim(ClaimState.Unavailable, a, held.takenAt), PlanClaims.resolve(plan, null, now).of(plan.item("b")!!))
    }

    @Test fun activeClaimsLeaveOutWhatHistoryAlreadySays() {
        val active = PlanClaims.resolve(plan, presence(live = true), now).active()
        assertEquals(setOf("b", "d"), active.claims.keys)
        assertEquals(ClaimState.Completed, PlanClaims.resolve(plan, presence(live = true), now).claims["e"]?.state)
    }
}
