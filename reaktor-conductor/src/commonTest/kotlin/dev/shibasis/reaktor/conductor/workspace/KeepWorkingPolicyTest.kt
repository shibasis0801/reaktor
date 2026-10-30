package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.*

class KeepWorkingPolicyTest {
    private val me = BoardActor(BoardActorKind.Agent, "Falcon", "codex:falcon", "agent:codex:falcon")
    private val other = BoardActor(BoardActorKind.Agent, "Tower", "claude:tower", "agent:claude:tower")
    private fun plan(vararg items: PlanItem) = WorkPlan("hangar:launch", "Launch", items = items.toList(), revision = 3,
        createdBy = BoardActor(BoardActorKind.Person, "Owner"), createdAt = 1, updatedAt = 1)

    @Test fun ownedDoingPrecedesTheAuthenticatedSchedulerCandidate() {
        val plan = plan(PlanItem("mine", "Mine", status = PlanStatus.Doing, by = me), PlanItem("ready", "Ready"),
            PlanItem("theirs", "Theirs", status = PlanStatus.Doing, by = other))
        val next = PlanNext(PlanSchedule.ready(plan).single())
        assertEquals(listOf("mine", "ready"), KeepWorkingPolicy.work(plan, me, next).map { it.id })
        assertTrue(KeepWorkingPolicy.reason(plan.thread, KeepWorkingPolicy.work(plan, me, next)).contains("queued or running check"))
    }

    @Test fun aLocalReadyItemNeverSubstitutesForAnAuthenticatedSchedulerDecision() {
        val plan = plan(PlanItem("free", "Free"))
        assertTrue(KeepWorkingPolicy.work(plan, me, PlanNext(reason = "Profile or seat does not match")).isEmpty())
        assertTrue(KeepWorkingPolicy.work(plan, me.copy(agent = null), PlanNext(PlanSchedule.ready(plan).single())).isEmpty())
    }

    @Test fun closedParentsDecisionsForeignClaimsAndWaitingWorkDoNotContinue() {
        val plan = plan(PlanItem("closed", "Closed", kind = PlanKind.Component, status = PlanStatus.Done),
            PlanItem("hidden", "Hidden", parent = "closed", status = PlanStatus.Doing, by = me),
            PlanItem("decision", "Owner decision", kind = PlanKind.Decision, status = PlanStatus.Doing, by = me),
            PlanItem("theirs", "Theirs", status = PlanStatus.Doing, by = other),
            PlanItem("wait", "Wait", after = listOf("missing"), status = PlanStatus.Doing, by = me))
        assertTrue(KeepWorkingPolicy.work(plan, me, PlanNext()).isEmpty())
    }

    @Test fun oldCandidateOrAnotherPlansCandidateIsIgnored() {
        val plan = plan(PlanItem("free", "Free"))
        val candidate = PlanSchedule.ready(plan).single()
        listOf(candidate.copy(revision = 1), candidate.copy(thread = "hangar:other")).forEach {
            assertTrue(KeepWorkingPolicy.work(plan, me, PlanNext(it)).isEmpty())
        }
    }

    @Test fun ownerStopWordsRequireTheStartOfTheMessageAndAWordBoundary() {
        listOf("STOP", " pause please", "halt", "hold this", "enough", "that's enough", "THATS ENOUGH").forEach {
            assertTrue(KeepWorkingPolicy.ownerStopped(it), it)
        }
        listOf("resume", "do not stop", "stopping a bug", "pauseButton", "finish then stop").forEach {
            assertFalse(KeepWorkingPolicy.ownerStopped(it), it)
        }
    }

    @Test fun progressIgnoresMessageOnlyRevisionsAndDetectsStatusOrClaimChanges() {
        val plan = plan(PlanItem("one", "One", status = PlanStatus.Doing, by = me))
        assertEquals(KeepWorkingPolicy.progress(plan), KeepWorkingPolicy.progress(plan.copy(revision = 99, updatedAt = 99)))
        assertNotEquals(KeepWorkingPolicy.progress(plan), KeepWorkingPolicy.progress(plan.copy(items = plan.items.map { it.copy(status = PlanStatus.Done) })))
        assertNotEquals(KeepWorkingPolicy.progress(plan), KeepWorkingPolicy.progress(plan.copy(items = plan.items.map { it.copy(by = other) })))
    }
}
