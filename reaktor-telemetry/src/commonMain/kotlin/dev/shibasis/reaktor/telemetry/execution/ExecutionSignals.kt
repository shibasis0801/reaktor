package dev.shibasis.reaktor.telemetry.execution

import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import kotlin.math.ceil
import kotlin.math.roundToLong

enum class SpanOutcome {
    Ok, Cancelled, Unauthorized, NotFound, Refused, Failed;

    val refusal: Boolean get() = this in setOf(Unauthorized, NotFound, Refused)
    val problem: Boolean get() = this == Failed || refusal

    companion object {
        fun of(status: Int): SpanOutcome = when {
            status == 499 -> Cancelled
            status == 401 || status == 403 -> Unauthorized
            status == 404 -> NotFound
            status in 400..498 -> Refused
            status >= 500 -> Failed
            else -> Ok
        }
    }
}

enum class SignalLevel { Failing, Degraded, Watch, Healthy, Background }

data class SignalKey(
    val island: String,
    val phase: ServiceExecutionPhase,
    val contract: String,
    val operation: String,
    val node: String,
) {
    val probe: Boolean get() = operation.substringBefore('.').uppercase() in setOf("HEAD", "OPTIONS")
    val id: String get() = listOf(island, phase.name, contract, operation, node).joinToString("|")
}

data class ExecutionSignal(
    val key: SignalKey,
    val level: SignalLevel,
    val finding: String,
    val calls: Int,
    val statuses: Map<Int, Int>,
    val p50Millis: Double?,
    val p95Millis: Double?,
    val maxMillis: Double?,
    val earlier: Int,
    val later: Int,
    val orphans: Int,
    val revisions: Set<String>,
    val sessions: Int,
    val traces: List<String>,
    val lastMillis: Long,
    val problemSessions: List<Int> = emptyList(),
) {
    val outcomes: Map<SpanOutcome, Int> get() = statuses.entries.groupingBy { SpanOutcome.of(it.key) }.fold(0) { sum, entry -> sum + entry.value }
    val failed: Int get() = outcomes[SpanOutcome.Failed] ?: 0
    val refused: Int get() = outcomes.filterKeys { it.refusal }.values.sum()
    val rising: Boolean get() = later >= ExecutionSignals.RisingCalls && later >= 2 * earlier.coerceAtLeast(1)
}

data class SignalReport(
    val signals: List<ExecutionSignal>,
    val spans: Int,
    val traces: Int,
    val fromMillis: Long?,
    val toMillis: Long?,
    val clientSpans: Int,
    val attributed: Int,
    val orphans: Int,
    val crossIsland: Int,
    val clientToServer: Int,
) {
    fun at(level: SignalLevel): List<ExecutionSignal> = signals.filter { it.level == level }
    val notable: List<ExecutionSignal> get() = signals.filter { it.level <= SignalLevel.Watch }
    val minutes: Double? get() = if (fromMillis == null || toMillis == null) null else (toMillis - fromMillis) / 60_000.0
}

object ExecutionSignals {
    const val SlowMillis = 3_000.0
    const val RisingCalls = 10
    private const val SteadyCalls = 5
    private const val ExampleTraces = 8

    fun of(spans: List<ServiceSpan>, slowMillis: Double = SlowMillis): SignalReport {
        val valid = spans.filter { it.traceId.isNotBlank() && it.spanId.isNotBlank() && it.durationMillis.isFinite() }
            .distinctBy { it.traceId to it.spanId }
        val retained = valid.mapTo(HashSet()) { it.traceId to it.spanId }
        val orphans = valid.filter { it.parentId.isNotBlank() && (it.traceId to it.parentId) !in retained }.mapTo(HashSet()) { it.traceId to it.spanId }
        val from = valid.minOfOrNull { it.startedMillis }
        val to = valid.maxOfOrNull { it.startedMillis }
        val middle = if (from == null || to == null) 0L else from + (to - from) / 2
        val signals = valid.groupBy { SignalKey(it.island, it.phase, it.contract, it.operation, it.node) }
            .map { (key, rows) -> signal(key, rows, middle, orphans, slowMillis) }
            .sortedWith(compareBy<ExecutionSignal> { it.level }.thenByDescending { it.failed + it.refused }.thenByDescending { it.calls })
        val traces = valid.groupBy { it.traceId }.values
        return SignalReport(signals, valid.size, traces.size, from, to,
            clientSpans = valid.count { it.phase == ServiceExecutionPhase.CLIENT },
            attributed = valid.count { it.node.isNotBlank() },
            orphans = orphans.size,
            crossIsland = traces.count { rows -> rows.mapTo(HashSet()) { it.island }.size > 1 },
            clientToServer = traces.count { rows -> rows.any { it.phase == ServiceExecutionPhase.CLIENT } && rows.any { it.phase == ServiceExecutionPhase.SERVER } })
    }

    private fun signal(key: SignalKey, rows: List<ServiceSpan>, middle: Long, orphans: Set<Pair<String, String>>, slowMillis: Double): ExecutionSignal {
        val durations = rows.filter { SpanOutcome.of(it.status) != SpanOutcome.Cancelled }.map { it.durationMillis.coerceAtLeast(0.0) }.sorted()
        val examples = rows.sortedWith(compareByDescending<ServiceSpan> { SpanOutcome.of(it.status).problem }
            .thenByDescending { it.durationMillis }).map { it.traceId }.distinct().take(ExampleTraces)
        val measured = ExecutionSignal(key, SignalLevel.Healthy, "", rows.size,
            rows.groupingBy { it.status }.eachCount().entries.sortedBy { it.key }.associate { it.key to it.value },
            durations.quantile(0.5), durations.quantile(0.95), durations.lastOrNull(),
            earlier = rows.count { it.startedMillis < middle }, later = rows.count { it.startedMillis >= middle },
            orphans = rows.count { (it.traceId to it.spanId) in orphans },
            revisions = rows.mapNotNullTo(LinkedHashSet()) { it.graphDigest.ifBlank { null } },
            sessions = rows.mapNotNullTo(HashSet()) { it.applicationSession.ifBlank { null } }.size,
            traces = examples, lastMillis = rows.maxOf { it.startedMillis },
            problemSessions = rows.filter { SpanOutcome.of(it.status).problem }.mapNotNull { it.applicationSession.ifBlank { null } }
                .groupingBy { it }.eachCount().values.sortedDescending())
        val level = level(measured, slowMillis)
        return measured.copy(level = level, finding = finding(measured, level, slowMillis))
    }

    private fun level(signal: ExecutionSignal, slowMillis: Double): SignalLevel {
        val slow = (signal.p95Millis ?: 0.0) >= slowMillis
        return when {
            signal.key.probe && signal.failed == 0 -> SignalLevel.Background
            signal.failed > 0 -> SignalLevel.Failing
            signal.calls >= SteadyCalls && signal.refused * 2 >= signal.calls -> SignalLevel.Degraded
            slow && signal.calls >= 3 -> SignalLevel.Degraded
            slow || signal.refused > 0 || signal.orphans > 0 || signal.rising -> SignalLevel.Watch
            else -> SignalLevel.Healthy
        }
    }

    private fun finding(signal: ExecutionSignal, level: SignalLevel, slowMillis: Double): String {
        val parts = buildList {
            if (signal.failed > 0) add("${signal.failed} of ${calls(signal.calls)} failed with ${statuses(signal) { it >= 500 }}")
            if (signal.refused > 0) add("${signal.refused} of ${calls(signal.calls)} refused with ${statuses(signal) { SpanOutcome.of(it).refusal }}")
            makers(signal)?.let(::add)
            signal.p95Millis?.takeIf { it >= slowMillis }?.let { add("p95 ${millis(it)}") }
            if (signal.orphans == 1) add("1 call names a caller whose span never arrived")
            if (signal.orphans > 1) add("${signal.orphans} calls name callers whose spans never arrived")
            if (signal.rising) add("rising from ${signal.earlier} to ${signal.later} between the halves of the window")
        }
        return when {
            level == SignalLevel.Background -> "${calls(signal.calls)} probing the island" + if (signal.refused > 0) "; ${signal.refused} refused" else ""
            parts.isEmpty() -> "${calls(signal.calls)}" + (signal.p50Millis?.let { ", p50 ${millis(it)}" } ?: "")
            else -> parts.joinToString("; ")
        }
    }

    private fun makers(signal: ExecutionSignal): String? {
        val problems = signal.failed + signal.refused
        val made = signal.problemSessions.sum()
        if (problems < 2 || made == 0) return null
        val sessions = if (signal.problemSessions.size == 1) "1 session" else "${signal.problemSessions.size} sessions"
        val busiest = signal.problemSessions.first().takeIf { signal.problemSessions.size > 1 && it >= RisingCalls }?.let { ", one $it times" }.orEmpty()
        return "$sessions made ${if (made == problems) "them" else "$made of them"}$busiest"
    }

    private fun statuses(signal: ExecutionSignal, wanted: (Int) -> Boolean) =
        signal.statuses.filterKeys(wanted).entries.sortedByDescending { it.value }.joinToString(", ") { (status, count) -> "HTTP $status ×$count" }

    private fun calls(count: Int) = if (count == 1) "1 call" else "$count calls"

    fun millis(value: Double): String = when {
        value >= 10_000 -> "${(value / 1000).roundToLong()} s"
        value >= 1_000 -> (value / 100).roundToLong().let { tenths -> "${tenths / 10}${if (tenths % 10 == 0L) "" else ".${tenths % 10}"} s" }
        else -> "${value.roundToLong()} ms"
    }

    fun window(minutes: Int): String = when {
        minutes >= 2_880 && minutes % 1_440 == 0 -> "${minutes / 1_440} d"
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "$minutes min"
    }

    private fun List<Double>.quantile(q: Double): Double? = if (isEmpty()) null else this[(ceil(size * q).toInt() - 1).coerceIn(0, size - 1)]
}
