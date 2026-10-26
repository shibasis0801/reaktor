package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlin.test.*

class TaskReviewPanelTest {
    private val candidate = "a".repeat(64)
    private val profiles = ReviewLenses.profiles.associateBy { it.id }
    private val resolved = TaskSeatResolver(profiles::getValue).resolve(TaskSeat(RuntimeKind.Codex, access = TaskAccess.Read,
        lenses = listOf("codex-correctness", "claude-security")))
    private fun finding(title: String, severity: String = "high", line: Int = 10, confirms: List<String> = emptyList(), receipt: String? = null) =
        TaskReview.Finding(title, "Evidence for $title", severity, "Source.kt", line, confirms, receipt)
    private fun review(findings: List<TaskReview.Finding>, revision: String = candidate) = ConductorJson.encodeToString(TaskReview.serializer(),
        TaskReview(if (findings.isEmpty()) "pass" else "repair", "Recorded independent verification", revision, findings))
    private fun outputs(verification: List<TaskReview.Finding>) = mapOf(
        "graph:lens-0" to review(listOf(finding("Duplicate A", "medium"), finding("Unconfirmed", "blocker", 30))),
        "graph:lens-1" to review(listOf(finding("Duplicate B"), finding("Another", "medium", 20))),
        "graph:verify" to review(verification))

    @Test fun duplicatesMergeUnconfirmedClaimsDropAndSeverityRanks() {
        val (review, findings) = TaskReviewPanel.reduce(candidate, "run", resolved, outputs(listOf(
            finding("Verified duplicate", confirms = listOf("lens-0:0", "lens-1:0")),
            finding("Verified other", "medium", 20, listOf("lens-1:1")))), emptyList())
        assertEquals("repair", review.verdict)
        assertEquals(listOf("high", "medium"), findings.map { it.severity })
        assertEquals(listOf("codex-correctness", "claude-security").toSet(), findings.first().lenses.toSet())
        assertTrue(findings.none { it.line == 30 })
        assertEquals("verify", findings.first().confirmation?.participant)
        assertEquals("run", findings.first().confirmation?.producerRunId)
        assertTrue("4 reported findings, 3 confirmed, 2 merged" in review.summary)
        assertEquals(findings, TaskReviewPanel.reduce(candidate, "run", resolved, outputs(listOf(
            finding("Verified duplicate", confirms = listOf("lens-0:0", "lens-1:0")),
            finding("Verified other", "medium", 20, listOf("lens-1:1")))), emptyList()).second)
    }

    @Test fun benchmarkObservationsRetainUnconfirmedClaimsWithoutPublishingThem() {
        val output = outputs(listOf(finding("Verified duplicate", confirms = listOf("lens-0:0", "lens-1:0"))))
        val observations = TaskReviewPanel.observations(candidate, "run", resolved, output, emptyList())
        assertEquals(1, observations.count { it.confirmation != null })
        assertEquals(setOf(20, 30), observations.filter { it.confirmation == null }.map { it.line }.toSet())
        assertEquals(listOf("codex-correctness"), observations.single { it.line == 30 }.lenses)
        assertTrue(TaskReviewPanel.reduce(candidate, "run", resolved, output, emptyList()).second.none { it.line == 30 })
        assertFails { TaskReviewPanel.observations("stale", "run", resolved, output, emptyList()) }
    }

    @Test fun noConfirmedFindingsPassWithoutPublishingTheRawClaims() {
        val (review, findings) = TaskReviewPanel.reduce(candidate, "run", resolved, outputs(emptyList()), emptyList())
        assertEquals("pass", review.verdict)
        assertTrue(findings.isEmpty())
    }

    @Test fun staleCandidateAndFabricatedOrMovedConfirmationsFail() {
        val valid = outputs(listOf(finding("Verified", confirms = listOf("lens-0:0"))))
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, valid + ("graph:lens-0" to review(emptyList(), "b".repeat(64))), emptyList()) }
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, outputs(listOf(finding("Fabricated", confirms = listOf("lens-9:0")))), emptyList()) }
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, outputs(listOf(finding("Moved", line = 11, confirms = listOf("lens-0:0")))), emptyList()) }
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, outputs(listOf(finding("No original"))), emptyList()) }
    }

    @Test fun aCheckConfirmationRequiresAnExactCandidateFailingReceipt() {
        val output = outputs(listOf(finding("Verified", confirms = listOf("lens-0:0"), receipt = "check")))
        val receipt = AgentCandidateCheck("check", candidate, CheckResult(listOf("test"), CheckOutcome.Failed, revision = candidate), "fixture")
        assertEquals("check", TaskReviewPanel.reduce(candidate, "run", resolved, output, listOf(receipt)).second.single().confirmation?.checkReceipt)
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, output, listOf(receipt.copy(candidateId = "old"))) }
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, output, listOf(receipt.copy(result = receipt.result.copy(revision = "old")))) }
        assertFails { TaskReviewPanel.reduce(candidate, "run", resolved, output, listOf(receipt.copy(result = receipt.result.copy(outcome = CheckOutcome.Passed)))) }
    }

    @Test fun workflowUsesBothProvidersReadOnlyBlindRootsAndAnExplicitVerificationJoin() {
        val definition = TaskReviewPanel.definition(resolved, candidate)
        definition.validate()
        assertEquals(setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode), definition.participants.map { it.runtime }.toSet())
        assertTrue(definition.participants.none { it.tools.allowWrites })
        assertTrue(definition.stages.take(2).all { stage -> definition.edges.none { it.to == stage.id } })
        assertEquals(setOf("lens-0", "lens-1"), definition.edges.filter { it.to == "verify" }.map { it.from }.toSet())
        assertEquals(setOf(candidate), definition.stages.map { it.candidateId }.toSet())
        assertTrue(definition.stages.first().instruction.contains("Do not publish through agent_finding"))
    }

    @Test fun lensResolutionPinsProfilesAndRejectsInvalidPanels() {
        assertEquals(listOf(1L, 1L), resolved.lenses.map { it.profileRevision })
        assertTrue(resolved.lenses.all { it.seat.access == TaskAccess.Read && !it.tools.allowWrites })
        assertFails { TaskSeatResolver(profiles::getValue).resolve(resolved.seat.copy(access = TaskAccess.Write)) }
        assertFails { TaskSeatResolver(profiles::getValue).resolve(resolved.seat.copy(lenses = listOf("codex-correctness", "codex-security"))) }
        assertFails { TaskSeatResolver(profiles::getValue).resolve(resolved.seat.copy(lenses = listOf("codex-correctness"))) }
        assertFails { TaskSeatResolver(profiles::getValue).resolve(resolved.seat.copy(lenses = listOf("codex-correctness", "codex-correctness"))) }
        assertEquals(resolved, ConductorJson.decodeFromString(ResolvedTaskSeat.serializer(), ConductorJson.encodeToString(ResolvedTaskSeat.serializer(), resolved)))
    }
}
