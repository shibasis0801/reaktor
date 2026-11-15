package dev.shibasis.reaktor.conductor.workspace

data class LedgerRuntime(val name: String)

data class LedgerSample(
    val harness: LedgerRuntime,
    val model: String? = null,
    val effort: String? = null,
    val tokens: Long? = null,
    val checks: Int = 0,
    val done: Int = 0,
    val steps: Int = 0,
    val doorCalls: Int = 0,
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val estimatedCostUsd: Double? = null,
)

data class LedgerBand(
    val harness: LedgerRuntime,
    val model: String,
    val effort: String?,
    val sessions: Int,
    val tokens: Long,
    val checks: Int,
    val done: Int,
    val steps: Int = 0,
    val doorCalls: Int = 0,
    val cachedShare: Double? = null,
    val meanEffectiveCostUsd: Double? = null,
    val usesEstimatedCost: Boolean = false,
) {
    val tokensPerSession: Long get() = LedgerScoring.tokensPerSession(tokens, sessions)
    val finished: Double? get() = LedgerScoring.finished(checks, done)
    val doorRatio: Double? get() = LedgerScoring.doorRatio(steps, doorCalls)
}

object LedgerScoring {
    fun tokensPerSession(tokens: Long, sessions: Int): Long = if (sessions == 0) 0 else tokens / sessions
    fun finished(checks: Int, done: Int): Double? = if (checks == 0) null else done.toDouble() / checks
    fun doorRatio(steps: Int, doorCalls: Int): Double? = if (steps == 0) null else doorCalls.toDouble() / steps.toDouble()
    fun standings(records: Collection<LedgerSample>): List<LedgerBand> =
        records.mapNotNull { record -> record.model?.let { Triple(record.harness, it, record.effort) to record } }
            .groupBy({ it.first }, { it.second })
            .map { (key, rows) ->
                val cachedShares = rows.mapNotNull { row ->
                    val inp = row.inputTokens?.takeIf { it > 0L } ?: return@mapNotNull null
                    val cached = row.cachedInputTokens ?: return@mapNotNull null
                    (cached.toDouble() / inp.toDouble()).coerceIn(0.0, 1.0)
                }
                val costs = rows.mapNotNull { it.costUsd ?: it.estimatedCostUsd }
                val estimated = rows.any { it.costUsd == null && it.estimatedCostUsd != null }
                LedgerBand(
                    harness = key.first,
                    model = key.second,
                    effort = key.third,
                    sessions = rows.size,
                    tokens = rows.sumOf { it.tokens ?: ((it.inputTokens ?: 0L) + (it.outputTokens ?: 0L)) },
                    checks = rows.sumOf { it.checks },
                    done = rows.sumOf { it.done },
                    steps = rows.sumOf { it.steps },
                    doorCalls = rows.sumOf { it.doorCalls },
                    cachedShare = cachedShares.takeIf { it.isNotEmpty() }?.average(),
                    meanEffectiveCostUsd = costs.takeIf { it.isNotEmpty() }?.average(),
                    usesEstimatedCost = estimated,
                )
            }
            .sortedWith(compareByDescending<LedgerBand> { it.sessions }.thenBy { it.model }.thenBy { it.effort.orEmpty() })
}
