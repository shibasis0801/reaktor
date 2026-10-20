package dev.shibasis.reaktor.telemetry.execution

import dev.shibasis.reaktor.service.ServiceSpan

enum class ParentCoverage { Root, Retained, Missing, Cycle }

data class ExecutionStep(
    val span: ServiceSpan,
    val depth: Int,
    val parentCoverage: ParentCoverage,
    val offsetMillis: Double,
    val selfMillis: Double?,
) {
    val id: String get() = "${span.traceId}:${span.spanId}"
    val durationMillis: Double get() = span.durationMillis.coerceAtLeast(0.0)
}

data class ExecutionTrace(
    val id: String,
    val startedMillis: Long,
    val elapsedMillis: Double,
    val steps: List<ExecutionStep>,
    val duplicateSpans: Int,
) {
    val failures: Int get() = steps.count { it.span.status >= 400 && it.span.status != 499 }
    val cancellations: Int get() = steps.count { it.span.status == 499 }
    val missingParents: Int get() = steps.count { it.parentCoverage == ParentCoverage.Missing }
    val cycles: Int get() = steps.count { it.parentCoverage == ParentCoverage.Cycle }
    val islands: Set<String> get() = steps.map { it.span.island }.filter(String::isNotBlank).toSet()
    val revisions: Set<String> get() = steps.map { it.span.graphDigest }.filter(String::isNotBlank).toSet()
    val sessions: Set<String> get() = steps.map { it.span.applicationSession }.filter(String::isNotBlank).toSet()
    val title: String get() = steps.firstOrNull { it.parentCoverage == ParentCoverage.Root }?.span?.operation
        ?: steps.first().span.operation

    companion object {
        fun retained(spans: List<ServiceSpan>): List<ExecutionTrace> = spans
            .filter { it.traceId.isNotBlank() && it.spanId.isNotBlank() && it.durationMillis.isFinite() }
            .groupBy(ServiceSpan::traceId)
            .map { (traceId, rows) ->
                val unique = rows.distinctBy(ServiceSpan::spanId).sortedWith(compareBy(ServiceSpan::startedMillis, ServiceSpan::spanId))
                val byId = unique.associateBy(ServiceSpan::spanId)
                val children = unique.groupBy(ServiceSpan::parentId)
                val started = unique.minOf(ServiceSpan::startedMillis)
                fun ancestry(span: ServiceSpan): Pair<Int, ParentCoverage> {
                    if (span.parentId.isBlank()) return 0 to ParentCoverage.Root
                    if (span.parentId !in byId) return 0 to ParentCoverage.Missing
                    val seen = hashSetOf(span.spanId)
                    var parent = span.parentId
                    var depth = 0
                    while (parent.isNotBlank()) {
                        if (!seen.add(parent)) return depth to ParentCoverage.Cycle
                        val retained = byId[parent] ?: break
                        depth++
                        parent = retained.parentId
                    }
                    return depth to ParentCoverage.Retained
                }
                val steps = unique.map { span ->
                    val (depth, coverage) = ancestry(span)
                    val offset = (span.startedMillis - started).toDouble()
                    ExecutionStep(span, depth, coverage, offset,
                        if (coverage == ParentCoverage.Cycle) null else selfTime(span, children[span.spanId].orEmpty()))
                }
                ExecutionTrace(traceId, started, steps.maxOf { it.offsetMillis + it.durationMillis }, steps, rows.size - unique.size)
            }.sortedByDescending(ExecutionTrace::startedMillis)

        private fun selfTime(span: ServiceSpan, children: List<ServiceSpan>): Double {
            val duration = span.durationMillis.coerceAtLeast(0.0)
            val intervals = children.mapNotNull { child ->
                val start = (child.startedMillis - span.startedMillis).toDouble().coerceAtLeast(0.0)
                val end = ((child.startedMillis - span.startedMillis).toDouble() + child.durationMillis.coerceAtLeast(0.0)).coerceAtMost(duration)
                if (end > start) start to end else null
            }.sortedBy { it.first }
            var covered = 0.0
            var end = 0.0
            intervals.forEach { (from, to) ->
                covered += (to - maxOf(from, end)).coerceAtLeast(0.0)
                end = maxOf(end, to)
            }
            return (duration - covered).coerceAtLeast(0.0)
        }
    }
}
