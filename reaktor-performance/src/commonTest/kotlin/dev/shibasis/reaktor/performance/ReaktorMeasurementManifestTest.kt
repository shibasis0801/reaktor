package dev.shibasis.reaktor.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReaktorMeasurementManifestTest {
    private val coldStart = ReaktorMeasurementRequirement(metric = "app.cold_start", unit = "ms")

    private fun report(
        metrics: List<ReaktorPerformanceMetric> = emptyList(),
        budgets: List<ReaktorPerformanceBudget> = emptyList(),
        toolRuns: List<ReaktorPerformanceToolRun> = emptyList(),
        target: String = "androidApp",
    ) = ReaktorPerformanceReport(
        target = target,
        generatedAt = "2026-09-13T00:00:00Z",
        metrics = metrics,
        budgets = budgets,
        toolRuns = toolRuns,
    )

    @Test
    fun aBudgetWithNoMeasurementNoLongerPassesSilently() {
        val budgets = listOf(ReaktorPerformanceBudget("app.cold_start", limit = 1200.0, unit = "ms"))
        val report = report(budgets = budgets)

        // The pre-existing budget path cannot see the gap.
        assertEquals(emptyList(), report.budgetViolations())

        // The manifest does.
        val status = report.measurementStatuses(listOf(coldStart)).single()
        assertEquals(ReaktorMeasurementOutcome.NoCollector, status.outcome)
        assertTrue(status.blocking)
        assertFailsWith<IllegalStateException> { report.requireMeasured(listOf(coldStart)) }
    }

    @Test
    fun aMeasuredValueWithinBudgetPasses() {
        val report = report(
            metrics = listOf(ReaktorPerformanceMetric("app.cold_start", 940.0, "ms")),
            budgets = listOf(ReaktorPerformanceBudget("app.cold_start", limit = 1200.0, unit = "ms")),
        )

        val status = report.measurementStatuses(listOf(coldStart)).single()
        assertEquals(ReaktorMeasurementOutcome.Passed, status.outcome)
        assertEquals(940.0, status.actual)
        report.requireMeasured(listOf(coldStart))
    }

    @Test
    fun aMeasuredValueOverBudgetIsDistinctFromNeverMeasured() {
        val report = report(
            metrics = listOf(ReaktorPerformanceMetric("app.cold_start", 1800.0, "ms")),
            budgets = listOf(ReaktorPerformanceBudget("app.cold_start", limit = 1200.0, unit = "ms")),
        )

        val status = report.measurementStatuses(listOf(coldStart)).single()
        assertEquals(ReaktorMeasurementOutcome.Exceeded, status.outcome)
        assertEquals(1800.0, status.actual)
        assertEquals(1200.0, status.limit)
    }

    @Test
    fun aFailedCollectorIsDistinctFromNoCollector() {
        val report = report(
            toolRuns = listOf(
                ReaktorPerformanceToolRun(
                    name = "macrobenchmark",
                    tool = ReaktorPerformanceTool.Gradle,
                    status = ReaktorPerformanceRunStatus.Failed,
                    startedAt = "2026-09-13T00:00:00Z",
                    durationMs = 12.0,
                    errorMessage = "device disconnected",
                )
            )
        )

        val status = report.measurementStatuses(listOf(coldStart)).single()
        assertEquals(ReaktorMeasurementOutcome.MeasurementFailed, status.outcome)
        assertTrue(status.message.contains("device disconnected"))
    }

    @Test
    fun aUnitMismatchDoesNotSatisfyTheRequirement() {
        val report = report(metrics = listOf(ReaktorPerformanceMetric("app.cold_start", 1.2, "s")))

        val status = report.measurementStatuses(listOf(coldStart)).single()
        assertEquals(ReaktorMeasurementOutcome.MeasurementFailed, status.outcome)
        assertTrue(status.message.contains("measured in s"))
    }

    @Test
    fun aMeasurementFromAnotherTargetDoesNotSatisfyATargetedRequirement() {
        val report = report(
            metrics = listOf(ReaktorPerformanceMetric("app.cold_start", 900.0, "ms")),
            target = "desktop",
        )
        val requirement = coldStart.copy(target = "androidApp")

        val status = report.measurementStatuses(listOf(requirement)).single()
        assertEquals(ReaktorMeasurementOutcome.MeasurementFailed, status.outcome)
        assertTrue(status.message.contains("desktop"))
    }

    @Test
    fun aWarningSeverityRequirementReportsWithoutBlocking() {
        val report = report()
        val requirement = coldStart.copy(severity = ReaktorPerformanceSeverity.Warning)

        val status = report.measurementStatuses(listOf(requirement)).single()
        assertEquals(ReaktorMeasurementOutcome.NoCollector, status.outcome)
        assertTrue(!status.blocking)
        report.requireMeasured(listOf(requirement))
    }
}
