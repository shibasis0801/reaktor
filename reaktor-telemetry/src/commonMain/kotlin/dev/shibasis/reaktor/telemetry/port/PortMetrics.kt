package dev.shibasis.reaktor.telemetry.port

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import kotlinx.atomicfu.atomic

/**
 * D11 — aggregate measures per port, collected at the call site.
 *
 * **Why this is not an OpenTelemetry meter.** `io.opentelemetry.kotlin` 0.1.0 ships tracing and
 * logging and no metrics API at all, so there is no meter to record into on the platforms the
 * KMP SDK exists to carry. `opentelemetry-java` has one, but only on JVM and Android.
 *
 * **Why it is not computed from the spans.** [dev.shibasis.reaktor.telemetry.export.ReaktorSpanProcessor]
 * drops spans when its queue is full and the workbench ledger is a bounded ring buffer, so counts
 * derived from retained spans under-report by exactly the amount nobody can see. Throughput and
 * error rate have to be counted where every call passes, which is the interceptor.
 *
 * This is the `M(g, Δt)` half of the telemetry model: an aggregate over the execution trace,
 * projected onto the architecture graph.
 *
 * **Cardinality.** Series are keyed on the stable architectural identities only — node, port,
 * contract, direction, shape. Never the activation or the node instance, which are per-process
 * and would make every restart a new series.
 */
class PortMetrics {
    private val series = atomic<Map<PortSeries, PortMeasurement>>(emptyMap())

    /** Records one completed call. Lock-free, so it never perturbs the call it is measuring. */
    fun record(port: Port<*>, durationNanos: Long, failed: Boolean, shape: PortSemantics) {
        val key = port.series(shape)
        while (true) {
            val current = series.value
            val updated = (current[key] ?: PortMeasurement(key)).plus(durationNanos, failed)
            if (series.compareAndSet(current, current + (key to updated))) return
        }
    }

    /** Every series measured so far, heaviest first. */
    fun snapshot(): List<PortMeasurement> = series.value.values.sortedByDescending { it.totalNanos }

    fun clear() {
        series.value = emptyMap()
    }

    val isEmpty: Boolean get() = series.value.isEmpty()
}

/** What one series is about. Only stable identities, so a restart continues the same series. */
data class PortSeries(
    val nodeId: String,
    val portId: String,
    val contractId: String,
    val direction: String,
    val shape: String,
) {
    val operation: String get() = "$contractId.$portId"
}

private fun Port<*>.series(shape: PortSemantics) = PortSeries(
    nodeId = (owner as? Unique)?.label.orEmpty().ifEmpty { "unlabelled" },
    portId = telemetryPortId(),
    contractId = type.type,
    direction = when (this) {
        is ConsumerPort<*> -> "consumer"
        is ProviderPort<*> -> "provider"
    },
    shape = shape.name.lowercase(),
)

/**
 * One port's measures over this process's lifetime.
 *
 * Latency is kept as a fixed-bucket histogram rather than a sample list: quantiles have to be
 * answerable without retaining every call, and a debugging surface that grows without limit
 * becomes the incident it was installed to diagnose.
 */
data class PortMeasurement(
    val series: PortSeries,
    val calls: Long = 0,
    val errors: Long = 0,
    val totalNanos: Long = 0,
    val maxNanos: Long = 0,
    val minNanos: Long = Long.MAX_VALUE,
    val buckets: List<Long> = List(Buckets.size + 1) { 0L },
) {
    fun plus(durationNanos: Long, failed: Boolean): PortMeasurement {
        val index = Buckets.indexOfFirst { durationNanos <= it }.let { if (it < 0) Buckets.size else it }
        return copy(
            calls = calls + 1,
            errors = errors + if (failed) 1 else 0,
            totalNanos = totalNanos + durationNanos,
            maxNanos = maxOf(maxNanos, durationNanos),
            minNanos = minOf(minNanos, durationNanos),
            buckets = buckets.mapIndexed { at, count -> if (at == index) count + 1 else count },
        )
    }

    val meanNanos: Long get() = if (calls == 0L) 0 else totalNanos / calls

    /**
     * An upper bound on the [fraction] quantile: the top of the bucket that quantile falls in.
     *
     * Never the quantile itself. Calls of 2µs land in the (1µs, 2.5µs] bucket, so p50 reads 2.5µs
     * — "p50 is at most 2.5µs" is true, "p50 is 2.5µs" is not. A quantile past the last bucket
     * returns null rather than a bound it cannot support; [maxNanos] is exact and covers that case.
     */
    fun quantileNanos(fraction: Double): Long? {
        if (calls == 0L) return null
        val target = (calls * fraction).toLong().coerceAtLeast(1)
        var seen = 0L
        buckets.forEachIndexed { at, count ->
            seen += count
            if (seen >= target) return Buckets.getOrNull(at)
        }
        return null
    }

    val errorRate: Double get() = if (calls == 0L) 0.0 else errors.toDouble() / calls

    companion object {
        /**
         * Explicit bucket bounds in nanoseconds, 1µs to 10s.
         *
         * A local port call is measured in microseconds — the median in the workbench's own graph
         * is about 2µs — so buckets that start at a millisecond would put every call in the first
         * one and report nothing.
         */
        val Buckets: List<Long> = listOf(
            1_000L, 2_500L, 5_000L, 10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L,
            1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 50_000_000L, 100_000_000L,
            500_000_000L, 1_000_000_000L, 10_000_000_000L,
        )
    }
}
