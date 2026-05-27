package dev.shibasis.reaktor.performance

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
class ReaktorPerformanceHarness(
    target: String,
    private val clock: Clock = Clock.System,
) {
    val collector = ReaktorPerformanceCollector(target, clock)

    fun <T> run(
        name: String,
        tool: ReaktorPerformanceTool = ReaktorPerformanceTool.Custom,
        reportPath: String? = null,
        scope: ReaktorPerformanceScope = ReaktorPerformanceScope(),
        block: () -> T,
    ): T {
        val startedAt = clock.now().toString()
        var result: T? = null
        var failure: Throwable? = null
        val duration = measureTime {
            try {
                result = block()
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }

        collector.toolRun(
            name = name,
            tool = tool,
            status = if (failure == null) ReaktorPerformanceRunStatus.Passed else ReaktorPerformanceRunStatus.Failed,
            startedAt = startedAt,
            durationMs = duration.inWholeNanoseconds / 1_000_000.0,
            reportPath = reportPath,
            errorMessage = failure?.message,
            scope = scope,
        )

        failure?.let { throw it }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun report(
        webVitals: ReaktorWebVitalsSnapshot? = null,
        appVitals: ReaktorAppVitalsSnapshot? = collector.recordedAppVitals(),
        serverVitals: ReaktorServerVitalsSnapshot? = null,
        budgets: List<ReaktorPerformanceBudget> = emptyList(),
    ): ReaktorPerformanceReport =
        collector.report(
            webVitals = webVitals,
            appVitals = appVitals,
            serverVitals = serverVitals,
            budgets = budgets,
        )

    fun budgetViolations(
        report: ReaktorPerformanceReport = report(),
    ): List<ReaktorPerformanceBudgetViolation> =
        report.budgetViolations()

    fun requireWithinBudgets(
        report: ReaktorPerformanceReport = report(),
    ) {
        val blocking = report.budgetViolations()
            .filter { it.severity == ReaktorPerformanceSeverity.Error }
        check(blocking.isEmpty()) {
            blocking.joinToString(separator = "\n") { it.message }
        }
    }
}
