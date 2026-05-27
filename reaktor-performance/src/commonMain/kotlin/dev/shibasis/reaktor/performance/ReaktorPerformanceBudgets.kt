package dev.shibasis.reaktor.performance

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

@Serializable
data class ReaktorPerformanceBudgetViolation(
    val sampleName: String,
    val medianMs: Double,
    val budgetMs: Double,
    val metric: String = sampleName,
    val actual: Double = medianMs,
    val limit: Double = budgetMs,
    val unit: String = "ms",
    val severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
    val domain: ReaktorPerformanceDomain = ReaktorPerformanceDomain.Runtime,
) {
    val message: String
        get() = "$metric ${actual.display(unit)} exceeded ${limit.display(unit)} budget"
}

fun ReaktorPerformanceReport.budgetViolations(): List<ReaktorPerformanceBudgetViolation> =
    buildList {
        samples.forEach { sample ->
            val budget = sample.budgetMs ?: return@forEach
            if (sample.medianMs > budget) {
                addViolation(
                    metric = sample.name,
                    actual = sample.medianMs,
                    limit = budget,
                    unit = sample.unit,
                    domain = ReaktorPerformanceDomain.Runtime,
                )
            }
        }

        metrics.forEach { metric ->
            val budget = metric.budgetLimit ?: return@forEach
            if (metric.value > budget) {
                addViolation(
                    metric = metric.name,
                    actual = metric.value,
                    limit = budget,
                    unit = metric.unit,
                    severity = metric.severity,
                    domain = metric.domain,
                )
            }
        }

        buildArtifacts.forEach { artifact ->
            val budget = artifact.budgetBytes
            if (budget != null && artifact.bytes > budget) {
                addViolation(
                    metric = artifact.name,
                    actual = artifact.bytes.toDouble(),
                    limit = budget.toDouble(),
                    unit = "bytes",
                    domain = ReaktorPerformanceDomain.Build,
                )
            }

            val compressedBudget = artifact.compressedBudgetBytes
            val compressedBytes = artifact.compressedBytes
            if (compressedBudget != null && compressedBytes != null && compressedBytes > compressedBudget) {
                addViolation(
                    metric = "${artifact.name}.compressed",
                    actual = compressedBytes.toDouble(),
                    limit = compressedBudget.toDouble(),
                    unit = "bytes",
                    domain = ReaktorPerformanceDomain.Build,
                )
            }
        }

        buildTimings.forEach { timing ->
            val budget = timing.budgetMs ?: return@forEach
            if (timing.durationMs > budget) {
                addViolation(
                    metric = timing.name,
                    actual = timing.durationMs,
                    limit = budget,
                    unit = "ms",
                    domain = ReaktorPerformanceDomain.Build,
                )
            }
        }

        budgets.forEach { budget ->
            val metric = metrics.lastOrNull { it.name == budget.metric }
                ?: return@forEach
            if (metric.value > budget.limit) {
                addViolation(
                    metric = budget.metric,
                    actual = metric.value,
                    limit = budget.limit,
                    unit = budget.unit,
                    severity = budget.severity,
                    domain = metric.domain,
                )
            }
        }
    }

private fun MutableList<ReaktorPerformanceBudgetViolation>.addViolation(
    metric: String,
    actual: Double,
    limit: Double,
    unit: String,
    severity: ReaktorPerformanceSeverity = ReaktorPerformanceSeverity.Error,
    domain: ReaktorPerformanceDomain,
) {
    add(
        ReaktorPerformanceBudgetViolation(
            sampleName = metric,
            medianMs = actual.round1(),
            budgetMs = limit.round1(),
            metric = metric,
            actual = actual.round1(),
            limit = limit.round1(),
            unit = unit,
            severity = severity,
            domain = domain,
        ),
    )
}

private fun Double.round1(): Double =
    (this * 10.0).roundToLong() / 10.0

private fun Double.display(unit: String): String =
    when (unit) {
        "bytes" -> "${roundToLong()} bytes"
        "count" -> "${roundToLong()} count"
        else -> "$this$unit"
    }
