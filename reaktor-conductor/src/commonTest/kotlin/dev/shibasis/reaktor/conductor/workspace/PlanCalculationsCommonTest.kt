package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanCalculationsCommonTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val agent = BoardActor(BoardActorKind.Agent, "codex", "codex:c1", agent = "test-agent")
    private fun plan(vararg items: PlanItem) = WorkPlan("hangar:test", "Signals", items = items.toList(), revision = 3, createdBy = person, createdAt = 1, updatedAt = 1)

    @Test fun readyCriticalSlackAndUnblocksMatchTheJvmSignalsFixture() {
        val plan = plan(PlanItem("first", "First", status = PlanStatus.Done), PlanItem("second", "Second", after = listOf("first")),
            PlanItem("third", "Third", after = listOf("second")), PlanItem("fourth", "Fourth", after = listOf("third")),
            PlanItem("side", "Side", after = listOf("first")), PlanItem("join", "Join", after = listOf("fourth", "side", "second")))
        val signals = PlanSignals.of(plan)
        assertEquals(mapOf(PlanSignals.Ready to "true", PlanSignals.Critical to "true", PlanSignals.Unblocks to "3", PlanSignals.Bottleneck to "true"), signals["second"])
        assertEquals(mapOf(PlanSignals.Ready to "true", PlanSignals.Slack to "2", PlanSignals.Unblocks to "1"), signals["side"])
        assertEquals("second", signals.getValue("third")[PlanSignals.Follows])
        assertEquals("fourth", signals.getValue("join")[PlanSignals.Follows])
        assertEquals("second", signals.getValue("join")[PlanSignals.AlsoAfter])
        assertNull(signals.getValue("first")[PlanSignals.Ready])
    }

    @Test fun cyclesMatchTheJvmSignalsFixture() {
        val signals = PlanSignals.of(plan(PlanItem("a", "A", after = listOf("b")), PlanItem("b", "B", after = listOf("a")), PlanItem("c", "C")))
        assertEquals("true", signals.getValue("a")[PlanSignals.Cycle])
        assertEquals("true", signals.getValue("b")[PlanSignals.Cycle])
        assertNull(signals.getValue("c")[PlanSignals.Cycle])
    }

    @Test fun graphScopesPortsAndStableIdsMatchTheJvmGraphFixture() {
        val held = plan(PlanItem("box", "Box", kind = PlanKind.Component), PlanItem("a", "A", parent = "box"), PlanItem("b", "B", parent = "box"),
            PlanItem("task", "Task", after = listOf("a"), replacement = "b", refs = listOf("file:fixture.kt"),
                relations = listOf(PlanRelation("a", "calls"), PlanRelation("b", "calls"), PlanRelation("a", "after a"))))
        val shape = PlanGraph.shape(listOf(held)) { ref -> if (ref == "file:fixture.kt") listOf("module:fixture") else emptyList() }
        assertTrue(shape.scopes.any { it.id == PlanIds.part(held.thread, "box") })
        assertTrue(shape.scopes.any { it.id == PlanIds.code(held.thread) })
        val ports = shape.wires.filter { it.consumer == PlanIds.item(held.thread, "task") }.map { it.consumerPort }.toSet()
        assertTrue(ports.containsAll(setOf("after a", "replaced by", "calls a", "calls b", "after a → a", "file:fixture.kt")))
        assertTrue(shape.nodes.any { it.id == PlanIds.ref(held.thread, "module:fixture") })
    }

    @Test fun nudgeChoicesMatchTheJvmWorkPlanFixture() {
        val now = 10 * 60 * 60_000L
        val design = plan(PlanItem("store", "Store", kind = PlanKind.Component, refs = listOf("module:reaktor-conductor")),
            PlanItem("kinds", "Kinds", parent = "store", refs = listOf("symbol:WorkPlan.state"))).copy(updatedAt = now - 40 * 60_000)
        fun kind(held: WorkPlan?, vararg files: String) = PlanNudges.of("claude:s1", held, now - 40 * 60_000,
            files.map { PlanEdit(it, now - 60_000) }, now, drawGuide = "Guide", designGuide = "Design")?.kind
        assertEquals(PlanNudges.Draw, kind(null, "modules/app/A.kt"))
        assertEquals("Hangar: this thread has no plan yet and you have edited 1 file. Guide",
            PlanNudges.of("claude:s1", null, now - 40 * 60_000, listOf(PlanEdit("modules/app/A.kt", now - 60_000)), now, drawGuide = "Guide", designGuide = "Design")?.text)
        assertNull(kind(null, ".claude/agents/critic.md"))
        assertEquals(PlanNudges.Flat, kind(plan(*(1..4).map { PlanItem("p$it", "P$it") }.toTypedArray())))
        assertEquals(PlanNudges.Doing, kind(design, "reaktor-conductor/src/WorkPlanStore.kt"))
        val working = design.copy(items = design.items.map { if (it.id == "kinds") it.copy(status = PlanStatus.Doing) else it })
        assertEquals(PlanNudges.Links, kind(working, "modules/app/A.kt", "modules/app/B.kt"))
        assertEquals(PlanNudges.Stale, kind(working, "reaktor-conductor/src/WorkPlan.kt"))
        assertNull(kind(working.copy(updatedAt = now - 5 * 60_000), "reaktor-conductor/src/WorkPlan.kt"))
    }

    @Test fun ledgerBandsAndCostScoringKeepTheJvmStandingValues() {
        val runtime = LedgerRuntime("ClaudeCode")
        val rows = listOf(LedgerSample(runtime, "claude-opus-5-5", "max", tokens = 10_000, checks = 4, done = 3),
            LedgerSample(runtime, "claude-opus-5-5", "max", tokens = 20_000, checks = 2, done = 1),
            LedgerSample(runtime, "claude-sonnet-5-5", "high", tokens = 4_000), LedgerSample(LedgerRuntime("Codex")))
        val bands = LedgerScoring.standings(rows)
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), bands.map { it.model })
        assertEquals(2, bands.first().sessions)
        assertEquals(15_000, bands.first().tokensPerSession)
        assertEquals(4.0 / 6, bands.first().finished)
        assertNull(bands.last().finished)
        val cost = LedgerScoring.standings(listOf(LedgerSample(runtime, "model", inputTokens = 100, cachedInputTokens = 50, outputTokens = 10, costUsd = 0.2),
            LedgerSample(runtime, "model", inputTokens = 200, cachedInputTokens = 100, outputTokens = 20, estimatedCostUsd = 0.4))).single()
        assertEquals(330, cost.tokens)
        assertEquals(0.5, cost.cachedShare)
        assertEquals(0.3, cost.meanEffectiveCostUsd!!, 0.000001)
        assertTrue(cost.usesEstimatedCost)
        val ties = LedgerScoring.standings(listOf(LedgerSample(runtime, "z", "high"), LedgerSample(runtime, "a", "high"),
            LedgerSample(runtime, "a"), LedgerSample(LedgerRuntime("Codex"), "a")))
        assertEquals(listOf("a", "a", "a", "z"), ties.map { it.model })
        assertEquals(listOf(null, null, "high", "high"), ties.map { it.effort })
        assertEquals(listOf("ClaudeCode", "Codex"), ties.take(2).map { it.harness.name })
    }

    @Test fun preemptionKeepsTheJvmUrgencyThresholdAndMessage() {
        val current = PlanCandidate("claude:held", 1, PlanItem("held", "Held", status = PlanStatus.Doing, by = agent, revision = 1), PlanPriority.Normal, false, 0, 1)
        val next = PlanCandidate("hangar:urgent", 2, PlanItem("urgent", "Urgent", revision = 2), PlanPriority.High, false, 0, 1)
        assertNull(PlanPreemption.choose(current, next))
        val urgent = next.copy(priority = PlanPriority.Urgent)
        val choice = PlanPreemption.choose(current, urgent)!!
        assertEquals("claude:held:held:1:hangar:urgent:urgent:2:Urgent", choice.key)
        assertEquals("plan:hangar:urgent#urgent", choice.item)
        assertTrue("Reach a safe checkpoint" in choice.text)
        assertTrue("Your current claim and running turn have not been changed." in choice.text)
        assertNull(PlanPreemption.choose(current.copy(item = current.item.copy(by = person)), urgent))
        assertNull(PlanPreemption.choose(current, next.copy(priority = current.priority)))
        val leaseActor = agent.copy(name = "Lease holder", session = "codex:lease")
        assertEquals(leaseActor, PlanPreemption.actor(current.copy(item = current.item.copy(lease = PlanLease(leaseActor, 1, 2)))))
    }

    @Test fun reviewRulesKeepTheJvmStructuredReviewDecisions() {
        val text = """{"verdict":"pass","summary":"Recorded independent verification","candidate":"candidate-1","findings":[]}"""
        assertEquals(TaskReview("pass", "Recorded independent verification", "candidate-1", emptyList()), TaskReview.parse(text))
        assertFailsWith<IllegalArgumentException> { TaskReview.parse(text.replace("pass", "unknown")) }
        assertFailsWith<IllegalArgumentException> { TaskReview.parse(text.replace("Recorded independent verification", "")) }
        assertFailsWith<IllegalArgumentException> { TaskReview.parse(text.replace("Recorded independent verification", "x".repeat(TaskGraphRules.OutcomeChars + 1))) }
    }
}
