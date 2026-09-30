package dev.shibasis.reaktor.performance

import kotlinx.serialization.Serializable

/**
 * A measurement a gate requires before it will pass.
 *
 * Budgets alone cannot gate a build: [ReaktorPerformanceBudgets] skips any budget whose metric
 * was never recorded, so declaring a threshold for a probe that does not exist produces a green
 * run. A requirement makes the absence itself a failure, and keeps "nothing measured" distinct
 * from "measured and fine".
 */
@Serializable
data class ReaktorMeasurementRequirement(
    val metric: String,
    val unit: String,
    /** The target that must have produced it, when a gate is target-specific. */
    val target: String? = null,
    /** The revision it must have been measured against, when a gate compares releases. */
    val revision: String? = null,
    val severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
)

/** The four states a gate must be able to tell apart. */
@Serializable
enum class ReaktorMeasurementOutcome {
    /** Measured, and within its budget — or no budget applies. */
    Passed,
    /** Measured, and outside its budget. */
    Exceeded,
    /** No collector produced this metric. A gate must not read this as success. */
    NoCollector,
    /** A collector ran and could not produce a usable value. */
    MeasurementFailed,
}

@Serializable
data class ReaktorMeasurementStatus(
    val metric: String,
    val outcome: ReaktorMeasurementOutcome,
    val unit: String,
    val actual: Double? = null,
    val limit: Double? = null,
    val severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
    val detail: String? = null,
) {
    val blocking: Boolean
        get() = severity == ReaktorPerformanceSeverity.Error &&
            outcome != ReaktorMeasurementOutcome.Passed

    val message: String
        get() = when (outcome) {
            ReaktorMeasurementOutcome.Passed ->
                "$metric measured${actual?.let { " at $it$unit" } ?: ""}"
            ReaktorMeasurementOutcome.Exceeded ->
                "$metric is ${actual}$unit, over its ${limit}$unit budget"
            ReaktorMeasurementOutcome.NoCollector ->
                "$metric was never measured${detail?.let { " ($it)" } ?: ""} — the gate cannot pass on absence"
            ReaktorMeasurementOutcome.MeasurementFailed ->
                "$metric could not be measured${detail?.let { ": $it" } ?: ""}"
        }
}

/**
 * Resolves each requirement against what the report actually contains.
 *
 * A metric that is present but carries a different unit, or was produced for a different target
 * or revision than the requirement names, counts as [ReaktorMeasurementOutcome.MeasurementFailed]
 * rather than silently satisfying the requirement.
 */
fun ReaktorPerformanceReport.measurementStatuses(
    requirements: List<ReaktorMeasurementRequirement>,
): List<ReaktorMeasurementStatus> = requirements.map { requirement ->
    val candidates = metrics.filter { it.name == requirement.metric }
    val failedRun = toolRuns.lastOrNull {
        it.status == ReaktorPerformanceRunStatus.Failed &&
            (requirement.target == null || it.scope.module == requirement.target)
    }

    if (candidates.isEmpty()) {
        val outcome = if (failedRun != null) {
            ReaktorMeasurementOutcome.MeasurementFailed
        } else {
            ReaktorMeasurementOutcome.NoCollector
        }
        return@map ReaktorMeasurementStatus(
            metric = requirement.metric,
            outcome = outcome,
            unit = requirement.unit,
            severity = requirement.severity,
            detail = failedRun?.let { "${it.name} failed: ${it.errorMessage ?: "no detail"}" },
        )
    }

    val metric = candidates.last()
    if (metric.unit != requirement.unit) {
        return@map ReaktorMeasurementStatus(
            metric = requirement.metric,
            outcome = ReaktorMeasurementOutcome.MeasurementFailed,
            unit = requirement.unit,
            actual = metric.value,
            severity = requirement.severity,
            detail = "measured in ${metric.unit}, required in ${requirement.unit}",
        )
    }
    if (requirement.target != null && target != requirement.target) {
        return@map ReaktorMeasurementStatus(
            metric = requirement.metric,
            outcome = ReaktorMeasurementOutcome.MeasurementFailed,
            unit = requirement.unit,
            actual = metric.value,
            severity = requirement.severity,
            detail = "measured on target '$target', required on '${requirement.target}'",
        )
    }

    val budget = budgets.lastOrNull { it.metric == requirement.metric }
    val exceeded = budget != null && when (budget.direction) {
        ReaktorPerformanceBudgetDirection.Max -> metric.value > budget.limit
        ReaktorPerformanceBudgetDirection.Min -> metric.value < budget.limit
    }
    ReaktorMeasurementStatus(
        metric = requirement.metric,
        outcome = if (exceeded) ReaktorMeasurementOutcome.Exceeded else ReaktorMeasurementOutcome.Passed,
        unit = requirement.unit,
        actual = metric.value,
        limit = budget?.limit,
        severity = budget?.severity ?: requirement.severity,
    )
}

/** Every requirement that blocks, in report order. */
fun ReaktorPerformanceReport.blockingMeasurements(
    requirements: List<ReaktorMeasurementRequirement>,
): List<ReaktorMeasurementStatus> = measurementStatuses(requirements).filter { it.blocking }

/**
 * Fails when a required measurement is missing, unusable, or over budget.
 *
 * Use this rather than [ReaktorPerformanceReport.budgetViolations] alone wherever a gate is
 * supposed to prove something was measured.
 */
fun ReaktorPerformanceReport.requireMeasured(
    requirements: List<ReaktorMeasurementRequirement>,
) {
    val blocking = blockingMeasurements(requirements)
    check(blocking.isEmpty()) { blocking.joinToString(separator = "\n") { it.message } }
}
