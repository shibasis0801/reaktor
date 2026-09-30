package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

@Serializable
data class UsageMetric(val reported: Long, val unknownTurns: Int)

@Serializable
data class UsageSummary(
    val turns: Int,
    val input: UsageMetric,
    val cachedInput: UsageMetric,
    val freshInput: UsageMetric,
    val cacheWriteInput: UsageMetric,
    val output: UsageMetric,
    val reasoningOutput: UsageMetric,
)

fun ThreadDocument.usageSummary(): UsageSummary {
    val turns = events.filter { it.author is Author.Agent }
    fun metric(value: (AgentUsage) -> Long?): UsageMetric {
        val values = turns.map { it.usage?.takeIf { usage -> usage.scope != UsageScope.ProviderSessionTotal }?.let(value)?.takeIf { number -> number >= 0 } }
        return UsageMetric(values.filterNotNull().sum(), values.count { it == null })
    }
    return UsageSummary(
        turns = turns.size,
        input = metric { it.inputTokens },
        cachedInput = metric { it.cachedInputTokens },
        freshInput = metric {
            val input = it.inputTokens
            val cached = it.cachedInputTokens
            if (input != null && cached != null && cached >= 0 && cached <= input) input - cached else null
        },
        cacheWriteInput = metric { it.cacheWriteInputTokens },
        output = metric { it.outputTokens },
        reasoningOutput = metric { it.reasoningOutputTokens },
    )
}

fun AgentUsage.forTurn(previous: AgentUsage?, freshSession: Boolean): AgentUsage {
    if (scope != UsageScope.ProviderSessionTotal) return this
    fun delta(current: Long?, before: Long?): Long? = when {
        current == null || current < 0 -> null
        freshSession -> current
        before == null || before < 0 || before > current -> null
        else -> current - before
    }
    return AgentUsage(
        inputTokens = delta(inputTokens, previous?.inputTokens),
        outputTokens = delta(outputTokens, previous?.outputTokens),
        cachedInputTokens = delta(cachedInputTokens, previous?.cachedInputTokens),
        cacheWriteInputTokens = delta(cacheWriteInputTokens, previous?.cacheWriteInputTokens),
        reasoningOutputTokens = delta(reasoningOutputTokens, previous?.reasoningOutputTokens),
        durationMillis = delta(durationMillis, previous?.durationMillis),
        costUsd = costUsd?.takeIf { it >= 0 }?.let { current ->
            if (freshSession) current else previous?.costUsd?.takeIf { it in 0.0..current }?.let { current - it }
        },
        scope = if (freshSession) UsageScope.Turn else UsageScope.ProviderSessionDelta,
    )
}
