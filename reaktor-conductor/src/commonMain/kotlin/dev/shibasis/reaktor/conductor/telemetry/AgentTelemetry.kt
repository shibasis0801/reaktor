package dev.shibasis.reaktor.conductor.telemetry

import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.UsageScope
import dev.shibasis.reaktor.conductor.forTurn
import dev.shibasis.reaktor.conductor.worklog.RateLimits
import dev.shibasis.reaktor.conductor.worklog.RateWindow
import dev.shibasis.reaktor.conductor.worklog.WorkUsageSample
import dev.shibasis.reaktor.conductor.workspace.ModelFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

@Serializable
data class ModelRateCard(
    val family: ModelFamily,
    val contextWindowTokens: Long,
    val freshInputPerMillionUsd: Double,
    val cachedInputPerMillionUsd: Double,
    val cacheWritePerMillionUsd: Double,
    val outputPerMillionUsd: Double,
)

object ModelTelemetryCatalog {
    private val claudeOpus1M = ModelRateCard(ModelFamily.Claude, 1_000_000L, 5.00, 0.50, 6.25, 25.00)
    private val claudeOpusLegacy = ModelRateCard(ModelFamily.Claude, 200_000L, 15.00, 1.50, 18.75, 75.00)
    private val claudeSonnet1M = ModelRateCard(ModelFamily.Claude, 1_000_000L, 3.00, 0.30, 3.75, 15.00)
    private val claudeSonnet200k = ModelRateCard(ModelFamily.Claude, 200_000L, 3.00, 0.30, 3.75, 15.00)
    private val claudeHaiku = ModelRateCard(ModelFamily.Claude, 200_000L, 1.00, 0.10, 1.25, 5.00)

    private val codexFlagship400k = ModelRateCard(ModelFamily.Codex, 400_000L, 2.50, 0.25, 2.50, 15.00)
    private val codexTerra400k = ModelRateCard(ModelFamily.Codex, 400_000L, 1.25, 0.125, 1.25, 10.00)
    private val codexLuna400k = ModelRateCard(ModelFamily.Codex, 400_000L, 0.40, 0.04, 0.40, 1.60)
    private val codex54Flagship = ModelRateCard(ModelFamily.Codex, 272_000L, 2.50, 0.25, 2.50, 15.00)
    private val codex54Mini = ModelRateCard(ModelFamily.Codex, 272_000L, 0.75, 0.075, 0.75, 4.50)
    private val codexReasoning200k = ModelRateCard(ModelFamily.Codex, 200_000L, 2.00, 0.50, 2.00, 8.00)

    private val geminiCarbon2M = ModelRateCard(ModelFamily.Gemini, 2_000_000L, 2.00, 0.20, 2.00, 12.00)
    private val geminiPro2M = ModelRateCard(ModelFamily.Gemini, 2_000_000L, 1.25, 0.125, 1.25, 10.00)
    private val geminiFlash1M = ModelRateCard(ModelFamily.Gemini, 1_000_000L, 0.30, 0.03, 0.30, 2.50)
    private val geminiFlashLite1M = ModelRateCard(ModelFamily.Gemini, 1_000_000L, 0.10, 0.01, 0.10, 0.40)

    fun resolve(model: String?, runtime: RuntimeKind? = null): ModelRateCard? {
        val id = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "<synthetic>" } ?: return null
        return when {
            "fable" in id || "opus-4-6" in id || "opus-4.6" in id || id == "opus" -> claudeOpus1M
            "opus" in id -> claudeOpusLegacy
            "verse" in id || "sonnet-4-6" in id || "sonnet-4.6" in id || id == "sonnet" -> claudeSonnet1M
            "sonnet" in id -> claudeSonnet200k
            "haiku" in id -> claudeHaiku
            "astra" in id || "gpt-6-sol" in id || id == "sol" -> codexFlagship400k
            "terra" in id -> codexTerra400k
            "luna" in id -> codexLuna400k
            "mini" in id && ("gpt-5" in id || "codex" in id) -> codex54Mini
            "gpt-5" in id || "codex" in id -> codex54Flagship
            id.startsWith("o3") || id.startsWith("o4") || id.startsWith("o1") -> codexReasoning200k
            "carbon" in id -> geminiCarbon2M
            "flash-lite" in id || "flash_lite" in id -> geminiFlashLite1M
            "flash" in id -> geminiFlash1M
            "argon" in id || "gemini" in id -> geminiPro2M
            else -> null
        }
    }

    fun contextWindowTokens(model: String?, runtime: RuntimeKind? = null): Long? =
        if (model.isNullOrBlank() && runtime == null) null else resolve(model, runtime)?.contextWindowTokens

    fun estimateCostUsd(
        model: String?,
        runtime: RuntimeKind? = null,
        freshInputTokens: Long?,
        cachedInputTokens: Long?,
        cacheWriteTokens: Long?,
        outputTokens: Long?,
    ): Double? {
        val card = if (model.isNullOrBlank()) null else resolve(model, runtime) ?: return null
        if (card == null) return null
        if (freshInputTokens == null && cachedInputTokens == null && cacheWriteTokens == null && outputTokens == null) return null
        val fresh = (freshInputTokens ?: 0L).coerceAtLeast(0L) * card.freshInputPerMillionUsd
        val cached = (cachedInputTokens ?: 0L).coerceAtLeast(0L) * card.cachedInputPerMillionUsd
        val written = (cacheWriteTokens ?: 0L).coerceAtLeast(0L) * card.cacheWritePerMillionUsd
        val output = (outputTokens ?: 0L).coerceAtLeast(0L) * card.outputPerMillionUsd
        return (fresh + cached + written + output) / 1_000_000.0
    }
}

@Serializable
data class TelemetrySample(
    val runtime: RuntimeKind,
    val family: ModelFamily,
    val requestId: String? = null,
    val at: Long? = null,
    val model: String? = null,
    val effort: String? = null,
    val inputTokens: Long? = null,
    val freshInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val contextTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val costUsd: Double? = null,
    val estimatedCostUsd: Double? = null,
    val durationMillis: Long? = null,
    val thinkingMillis: Long? = null,
    val subagent: Boolean = false,
    val compacted: Boolean = false,
    val scope: UsageScope = UsageScope.Turn,
) {
    val contextUtilizationPercent: Double?
        get() = if (contextTokens != null && contextWindowTokens != null && contextWindowTokens > 0L) {
            (contextTokens.toDouble() * 100.0) / contextWindowTokens.toDouble()
        } else null

    val cacheHitRatio: Double?
        get() = if (inputTokens != null && inputTokens > 0L && cachedInputTokens != null) {
            cachedInputTokens.toDouble() / inputTokens.toDouble()
        } else null

    val hasTokens: Boolean
        get() = inputTokens != null || outputTokens != null || cachedInputTokens != null ||
            cacheWriteInputTokens != null || reasoningOutputTokens != null || contextTokens != null

    fun toAgentUsage(): AgentUsage = AgentUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cachedInputTokens,
        cacheWriteInputTokens = cacheWriteInputTokens,
        reasoningOutputTokens = reasoningOutputTokens,
        costUsd = costUsd,
        durationMillis = durationMillis,
        scope = scope,
    )

    fun toWorkUsageSample(): WorkUsageSample = WorkUsageSample(
        id = requestId,
        at = at,
        context = contextTokens,
        output = outputTokens,
        reasoning = reasoningOutputTokens,
        input = inputTokens,
        costUsd = costUsd,
        cached = cachedInputTokens,
        fresh = freshInputTokens,
        cacheWrite = cacheWriteInputTokens,
        contextWindow = contextWindowTokens,
        estimatedCostUsd = estimatedCostUsd,
        model = model,
    )
}

@Serializable
data class TelemetryRollup(
    val requests: Int = 0,
    val subagentRequests: Int = 0,
    val inputTokens: Long? = null,
    val freshInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val peakContextTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val peakContextPercent: Double? = null,
    val costUsd: Double? = null,
    val estimatedCostUsd: Double? = null,
    val compactions: Int = 0,
    val unknownUsageRequests: Int = 0,
)

fun Iterable<TelemetrySample>.rollupTelemetry(): TelemetryRollup {
    val list = toList()
    if (list.isEmpty()) return TelemetryRollup()
    fun sumLong(pick: (TelemetrySample) -> Long?): Long? =
        list.mapNotNull(pick).takeIf { it.isNotEmpty() }?.sum()
    fun sumDouble(pick: (TelemetrySample) -> Double?): Double? =
        list.mapNotNull(pick).filter { it.isFinite() && it >= 0.0 }.takeIf { it.isNotEmpty() }?.sum()
    val tokenRows = list.filter { it.hasTokens || it.costUsd != null || !it.compacted }
    val peakSample = list.filter { it.contextTokens != null }.maxByOrNull { it.contextTokens ?: 0L }
    val peakContext = peakSample?.contextTokens
    val window = peakSample?.contextWindowTokens ?: list.mapNotNull { it.contextWindowTokens }.maxOrNull()
    val peakPercent = list.mapNotNull { it.contextUtilizationPercent }.maxOrNull()
        ?: if (peakContext != null && window != null && window > 0L) (peakContext.toDouble() * 100.0) / window.toDouble() else null
    return TelemetryRollup(
        requests = tokenRows.count { it.hasTokens || it.costUsd != null },
        subagentRequests = tokenRows.count { it.subagent && (it.hasTokens || it.costUsd != null) },
        inputTokens = sumLong { it.inputTokens },
        freshInputTokens = sumLong { it.freshInputTokens },
        cachedInputTokens = sumLong { it.cachedInputTokens },
        cacheWriteInputTokens = sumLong { it.cacheWriteInputTokens },
        outputTokens = sumLong { it.outputTokens },
        reasoningOutputTokens = sumLong { it.reasoningOutputTokens },
        peakContextTokens = peakContext,
        contextWindowTokens = window,
        peakContextPercent = peakPercent,
        costUsd = sumDouble { it.costUsd },
        estimatedCostUsd = sumDouble { it.estimatedCostUsd },
        compactions = list.count { it.compacted },
        unknownUsageRequests = list.count { !it.compacted && !it.hasTokens && it.costUsd == null },
    )
}

@Serializable
sealed interface ProviderTelemetry {
    val family: ModelFamily
    val runtime: RuntimeKind
    val requestId: String?
    val timestampMillis: Long?
    val model: String?
    val effort: String?
    val subagent: Boolean
    val compacted: Boolean
    val scope: UsageScope

    fun normalize(): TelemetrySample
    fun toAgentUsage(): AgentUsage

    @Serializable
    @SerialName("claude")
    data class Claude(
        override val requestId: String? = null,
        override val timestampMillis: Long? = null,
        override val model: String? = null,
        override val effort: String? = null,
        val freshInputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val cacheCreationTokens: Long? = null,
        val outputTokens: Long? = null,
        val thinkingTokens: Long? = null,
        val thinkingMillis: Long? = null,
        val durationMillis: Long? = null,
        val costUsd: Double? = null,
        val serviceTier: String? = null,
        val rateLimits: RateLimits? = null,
        override val subagent: Boolean = false,
        override val compacted: Boolean = false,
        override val scope: UsageScope = UsageScope.Turn,
    ) : ProviderTelemetry {
        override val family: ModelFamily get() = ModelFamily.Claude
        override val runtime: RuntimeKind get() = RuntimeKind.ClaudeCode

        val strictInclusiveInputTokens: Long?
            get() = if (freshInputTokens != null && cacheReadTokens != null && cacheCreationTokens != null) {
                freshInputTokens + cacheReadTokens + cacheCreationTokens
            } else null

        val inclusiveInputTokens: Long?
            get() = freshInputTokens?.let { it + (cacheReadTokens ?: 0L) + (cacheCreationTokens ?: 0L) }

        val contextTokens: Long?
            get() = ((freshInputTokens ?: 0L) + (cacheReadTokens ?: 0L) + (cacheCreationTokens ?: 0L))
                .takeIf { it > 0L && (freshInputTokens != null || cacheReadTokens != null || cacheCreationTokens != null) }

        override fun toAgentUsage(): AgentUsage = AgentUsage(
            inputTokens = strictInclusiveInputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cacheReadTokens,
            cacheWriteInputTokens = cacheCreationTokens,
            reasoningOutputTokens = thinkingTokens,
            costUsd = costUsd,
            durationMillis = durationMillis,
            scope = scope,
        )

        override fun normalize(): TelemetrySample {
            val window = ModelTelemetryCatalog.contextWindowTokens(model, runtime)
            val estimated = ModelTelemetryCatalog.estimateCostUsd(
                model = model,
                runtime = runtime,
                freshInputTokens = freshInputTokens,
                cachedInputTokens = cacheReadTokens,
                cacheWriteTokens = cacheCreationTokens,
                outputTokens = outputTokens,
            )
            return TelemetrySample(
                runtime = runtime,
                family = family,
                requestId = requestId,
                at = timestampMillis,
                model = model,
                effort = effort,
                inputTokens = inclusiveInputTokens,
                freshInputTokens = freshInputTokens,
                cachedInputTokens = cacheReadTokens,
                cacheWriteInputTokens = cacheCreationTokens,
                outputTokens = outputTokens,
                reasoningOutputTokens = thinkingTokens,
                contextTokens = contextTokens,
                contextWindowTokens = window,
                costUsd = costUsd,
                estimatedCostUsd = estimated,
                durationMillis = durationMillis,
                thinkingMillis = thinkingMillis,
                subagent = subagent,
                compacted = compacted,
                scope = scope,
            )
        }
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override val requestId: String? = null,
        override val timestampMillis: Long? = null,
        override val model: String? = null,
        override val effort: String? = null,
        val inclusiveInputTokens: Long? = null,
        val cachedInputTokens: Long? = null,
        val cacheWriteInputTokens: Long? = null,
        val outputTokens: Long? = null,
        val reasoningOutputTokens: Long? = null,
        val totalTokens: Long? = null,
        val contextTokens: Long? = null,
        val durationMillis: Long? = null,
        val costUsd: Double? = null,
        val rateLimits: RateLimits? = null,
        val creditBalance: Double? = null,
        override val subagent: Boolean = false,
        override val compacted: Boolean = false,
        override val scope: UsageScope = UsageScope.Turn,
    ) : ProviderTelemetry {
        override val family: ModelFamily get() = ModelFamily.Codex
        override val runtime: RuntimeKind get() = RuntimeKind.Codex

        val freshInputTokens: Long?
            get() = if (inclusiveInputTokens != null && cachedInputTokens != null && cachedInputTokens in 0L..inclusiveInputTokens) {
                inclusiveInputTokens - cachedInputTokens
            } else null

        override fun toAgentUsage(): AgentUsage = AgentUsage(
            inputTokens = inclusiveInputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            cacheWriteInputTokens = cacheWriteInputTokens,
            reasoningOutputTokens = reasoningOutputTokens,
            costUsd = costUsd,
            durationMillis = durationMillis,
            scope = scope,
        )

        override fun normalize(): TelemetrySample {
            val resolvedContext = contextTokens ?: inclusiveInputTokens
            val window = ModelTelemetryCatalog.contextWindowTokens(model, runtime)
            val estimated = ModelTelemetryCatalog.estimateCostUsd(
                model = model,
                runtime = runtime,
                freshInputTokens = freshInputTokens,
                cachedInputTokens = cachedInputTokens,
                cacheWriteTokens = cacheWriteInputTokens,
                outputTokens = outputTokens,
            )
            return TelemetrySample(
                runtime = runtime,
                family = family,
                requestId = requestId,
                at = timestampMillis,
                model = model,
                effort = effort,
                inputTokens = inclusiveInputTokens,
                freshInputTokens = freshInputTokens,
                cachedInputTokens = cachedInputTokens,
                cacheWriteInputTokens = cacheWriteInputTokens,
                outputTokens = outputTokens,
                reasoningOutputTokens = reasoningOutputTokens,
                contextTokens = resolvedContext,
                contextWindowTokens = window,
                costUsd = costUsd,
                estimatedCostUsd = estimated,
                durationMillis = durationMillis,
                subagent = subagent,
                compacted = compacted,
                scope = scope,
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override val requestId: String? = null,
        override val timestampMillis: Long? = null,
        override val model: String? = null,
        override val effort: String? = null,
        val stepIndex: Int? = null,
        val freshInputTokens: Long? = null,
        val inclusiveInputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val cacheWriteTokens: Long? = null,
        val outputTokens: Long? = null,
        val thinkingTokens: Long? = null,
        val durationMillis: Long? = null,
        val costUsd: Double? = null,
        val truncatedFields: List<String> = emptyList(),
        val rateLimits: RateLimits? = null,
        override val subagent: Boolean = false,
        override val compacted: Boolean = false,
        override val scope: UsageScope = UsageScope.Turn,
    ) : ProviderTelemetry {
        override val family: ModelFamily get() = ModelFamily.Gemini
        override val runtime: RuntimeKind get() = RuntimeKind.Gemini

        val resolvedInclusiveInputTokens: Long?
            get() = inclusiveInputTokens ?: freshInputTokens?.let { it + (cacheReadTokens ?: 0L) }

        val resolvedFreshInputTokens: Long?
            get() = freshInputTokens ?: when {
                inclusiveInputTokens == null -> null
                cacheReadTokens == null -> inclusiveInputTokens
                cacheReadTokens in 0L..inclusiveInputTokens -> inclusiveInputTokens - cacheReadTokens
                else -> null
            }

        val contextTokens: Long?
            get() = resolvedInclusiveInputTokens ?: cacheReadTokens?.takeIf { it > 0L }

        override fun toAgentUsage(): AgentUsage = AgentUsage(
            inputTokens = resolvedInclusiveInputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cacheReadTokens,
            cacheWriteInputTokens = cacheWriteTokens,
            reasoningOutputTokens = thinkingTokens,
            costUsd = costUsd,
            durationMillis = durationMillis,
            scope = scope,
        )

        override fun normalize(): TelemetrySample {
            val window = ModelTelemetryCatalog.contextWindowTokens(model, runtime)
            val estimated = ModelTelemetryCatalog.estimateCostUsd(
                model = model,
                runtime = runtime,
                freshInputTokens = resolvedFreshInputTokens,
                cachedInputTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                outputTokens = outputTokens,
            )
            return TelemetrySample(
                runtime = runtime,
                family = family,
                requestId = requestId,
                at = timestampMillis,
                model = model,
                effort = effort,
                inputTokens = resolvedInclusiveInputTokens,
                freshInputTokens = resolvedFreshInputTokens,
                cachedInputTokens = cacheReadTokens,
                cacheWriteInputTokens = cacheWriteTokens,
                outputTokens = outputTokens,
                reasoningOutputTokens = thinkingTokens,
                contextTokens = contextTokens,
                contextWindowTokens = window,
                costUsd = costUsd,
                estimatedCostUsd = estimated,
                durationMillis = durationMillis,
                subagent = subagent,
                compacted = compacted,
                scope = scope,
            )
        }
    }
}

sealed interface AgentTelemetry {
    val family: ModelFamily
    val runtime: RuntimeKind

    fun parseRecord(record: JsonObject, fallbackModel: String? = null, subagent: Boolean = false): ProviderTelemetry?
    fun parseApiUsage(
        usage: JsonObject,
        model: String? = null,
        scope: UsageScope = UsageScope.Turn,
        costUsd: Double? = null,
        durationMillis: Long? = null,
    ): ProviderTelemetry
    fun parseRateLimits(record: JsonObject, fallbackObservedAt: Long? = null): RateLimits?

    companion object {
        val all: List<AgentTelemetry> = listOf(ClaudeTelemetry, CodexTelemetry, GeminiTelemetry)

        fun of(runtime: RuntimeKind): AgentTelemetry? = when (runtime) {
            RuntimeKind.ClaudeCode -> ClaudeTelemetry
            RuntimeKind.Codex -> CodexTelemetry
            RuntimeKind.Gemini, RuntimeKind.ChatGptGemini -> GeminiTelemetry
            RuntimeKind.Gateway, RuntimeKind.Local, RuntimeKind.Echo -> null
        }

        fun of(family: ModelFamily): AgentTelemetry? = when (family) {
            ModelFamily.Claude -> ClaudeTelemetry
            ModelFamily.Codex -> CodexTelemetry
            ModelFamily.Gemini -> GeminiTelemetry
            ModelFamily.DeepSeek, ModelFamily.Kimi, ModelFamily.Sarvam -> null
        }
    }
}

data object ClaudeTelemetry : AgentTelemetry {
    override val family: ModelFamily = ModelFamily.Claude
    override val runtime: RuntimeKind = RuntimeKind.ClaudeCode

    data class MessageDelta(
        val merged: ProviderTelemetry.Claude,
        val delta: TelemetrySample,
    )

    class SessionCursor(private val defaultModel: String? = null) {
        private val highWater = linkedMapOf<String, ProviderTelemetry.Claude>()
        var latestRateLimits: RateLimits? = null
            private set

        fun ingest(record: JsonObject, subagentOverride: Boolean = false): MessageDelta? {
            parseRateLimits(record)?.let { latestRateLimits = it }
            val parsed = parseRecord(record, defaultModel, subagentOverride) as? ProviderTelemetry.Claude ?: return null
            if (parsed.compacted) return MessageDelta(parsed, parsed.normalize())
            val id = parsed.requestId ?: return MessageDelta(parsed, parsed.normalize())
            val previous = highWater[id]
            val merged = if (previous == null) parsed else mergeHighWater(previous, parsed)
            highWater[id] = merged
            val previousSample = previous?.normalize()
            val mergedSample = merged.normalize()
            val stamp = parsed.timestampMillis ?: mergedSample.at
            val delta = if (previousSample == null) {
                mergedSample.copy(at = stamp)
            } else if (merged == previous) {
                mergedSample.copy(
                    requestId = "$id:${stamp ?: 0L}:noop",
                    at = stamp,
                    inputTokens = null,
                    freshInputTokens = null,
                    cachedInputTokens = null,
                    cacheWriteInputTokens = null,
                    outputTokens = null,
                    reasoningOutputTokens = null,
                    contextTokens = null,
                    costUsd = null,
                    estimatedCostUsd = null,
                )
            } else {
                mergedSample.copy(
                    requestId = "$id:${stamp ?: mergedSample.outputTokens ?: 0L}",
                    at = stamp,
                    inputTokens = deltaCount(mergedSample.inputTokens, previousSample.inputTokens),
                    freshInputTokens = deltaCount(mergedSample.freshInputTokens, previousSample.freshInputTokens),
                    cachedInputTokens = deltaCount(mergedSample.cachedInputTokens, previousSample.cachedInputTokens),
                    cacheWriteInputTokens = deltaCount(mergedSample.cacheWriteInputTokens, previousSample.cacheWriteInputTokens),
                    outputTokens = deltaCount(mergedSample.outputTokens, previousSample.outputTokens),
                    reasoningOutputTokens = deltaCount(mergedSample.reasoningOutputTokens, previousSample.reasoningOutputTokens),
                    costUsd = deltaDouble(mergedSample.costUsd, previousSample.costUsd),
                    estimatedCostUsd = deltaDouble(mergedSample.estimatedCostUsd, previousSample.estimatedCostUsd),
                )
            }
            return MessageDelta(merged, delta)
        }

        fun highWaterMessages(): Collection<ProviderTelemetry.Claude> = highWater.values

        fun aggregatedLiveUsage(): AgentUsage {
            fun sum(pick: (AgentUsage) -> Long?): Long? =
                highWater.values.mapNotNull { pick(it.toAgentUsage()) }.takeIf { it.isNotEmpty() }?.sum()
            val costs = highWater.values.mapNotNull { it.costUsd }.filter { it.isFinite() && it >= 0.0 }
            return AgentUsage(
                inputTokens = sum { it.inputTokens },
                outputTokens = sum { it.outputTokens },
                cachedInputTokens = sum { it.cachedInputTokens },
                cacheWriteInputTokens = sum { it.cacheWriteInputTokens },
                reasoningOutputTokens = sum { it.reasoningOutputTokens },
                costUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }

    fun mergeHighWater(first: ProviderTelemetry.Claude, second: ProviderTelemetry.Claude): ProviderTelemetry.Claude =
        ProviderTelemetry.Claude(
            requestId = first.requestId ?: second.requestId,
            timestampMillis = minMillis(first.timestampMillis, second.timestampMillis),
            model = second.model ?: first.model,
            effort = second.effort ?: first.effort,
            freshInputTokens = maxCount(first.freshInputTokens, second.freshInputTokens),
            cacheReadTokens = maxCount(first.cacheReadTokens, second.cacheReadTokens),
            cacheCreationTokens = maxCount(first.cacheCreationTokens, second.cacheCreationTokens),
            outputTokens = maxCount(first.outputTokens, second.outputTokens),
            thinkingTokens = maxCount(first.thinkingTokens, second.thinkingTokens),
            thinkingMillis = maxCount(first.thinkingMillis, second.thinkingMillis),
            durationMillis = maxCount(first.durationMillis, second.durationMillis),
            costUsd = maxDouble(first.costUsd, second.costUsd),
            serviceTier = second.serviceTier ?: first.serviceTier,
            rateLimits = second.rateLimits ?: first.rateLimits,
            subagent = first.subagent || second.subagent,
            compacted = first.compacted || second.compacted,
            scope = second.scope,
        )

    override fun parseRecord(record: JsonObject, fallbackModel: String?, subagent: Boolean): ProviderTelemetry? {
        val at = parseIsoEpochMillis(record.str("timestamp"))
        val isSubagent = subagent || record.str("agentId") != null
        return when (record.str("type")) {
            "assistant" -> {
                val message = record.obj("message") ?: return null
                val rawModel = message.str("model")
                if (rawModel == "<synthetic>") return null
                val usage = message.obj("usage")
                val id = message.str("id")
                val cost = record.nonNegativeDouble("costUSD")
                ProviderTelemetry.Claude(
                    requestId = id,
                    timestampMillis = at,
                    model = rawModel ?: fallbackModel,
                    freshInputTokens = usage?.nonNegativeLong("input_tokens"),
                    cacheReadTokens = usage?.nonNegativeLong("cache_read_input_tokens"),
                    cacheCreationTokens = usage?.nonNegativeLong("cache_creation_input_tokens"),
                    outputTokens = usage?.nonNegativeLong("output_tokens"),
                    thinkingTokens = usage?.obj("output_tokens_details")?.nonNegativeLong("thinking_tokens"),
                    thinkingMillis = record.nonNegativeLong("thinkingDurationMs"),
                    costUsd = cost,
                    serviceTier = usage?.str("service_tier"),
                    subagent = isSubagent,
                    scope = UsageScope.Turn,
                )
            }
            "result" -> {
                val usage = record.obj("usage") ?: JsonObject(emptyMap())
                parseApiUsage(
                    usage = usage,
                    model = fallbackModel,
                    scope = UsageScope.Turn,
                    costUsd = record.nonNegativeDouble("total_cost_usd"),
                    durationMillis = record.nonNegativeLong("duration_ms"),
                )
            }
            "system" -> if (record.str("subtype") == "compact_boundary") {
                ProviderTelemetry.Claude(timestampMillis = at, model = fallbackModel, subagent = isSubagent, compacted = true)
            } else null
            "user" -> if (record.bool("isCompactSummary") == true && record.bool("isMeta") != true) {
                ProviderTelemetry.Claude(timestampMillis = at, model = fallbackModel, subagent = isSubagent, compacted = true)
            } else null
            else -> null
        }
    }

    override fun parseApiUsage(
        usage: JsonObject,
        model: String?,
        scope: UsageScope,
        costUsd: Double?,
        durationMillis: Long?,
    ): ProviderTelemetry.Claude = ProviderTelemetry.Claude(
        model = model?.takeUnless { it == "<synthetic>" },
        freshInputTokens = usage.nonNegativeLong("input_tokens"),
        cacheReadTokens = usage.nonNegativeLong("cache_read_input_tokens"),
        cacheCreationTokens = usage.nonNegativeLong("cache_creation_input_tokens"),
        outputTokens = usage.nonNegativeLong("output_tokens"),
        thinkingTokens = usage.obj("output_tokens_details")?.nonNegativeLong("thinking_tokens"),
        durationMillis = durationMillis,
        costUsd = costUsd?.takeIf { it.isFinite() && it >= 0.0 },
        serviceTier = usage.str("service_tier"),
        scope = scope,
    )

    override fun parseRateLimits(record: JsonObject, fallbackObservedAt: Long?): RateLimits? {
        if (record.str("type") != "rate_limit_event") return null
        val info = record.obj("rate_limit_info") ?: return null
        val at = parseIsoEpochMillis(record.str("timestamp")) ?: fallbackObservedAt
        val resetSeconds = info.nonNegativeLong("resetsAt")
        return RateLimits(
            windows = emptyList(),
            at = at,
            status = info.str("status"),
            resetsAt = resetSeconds?.times(1000L),
            overage = info.bool("isUsingOverage") ?: false,
        )
    }
}

data object CodexTelemetry : AgentTelemetry {
    override val family: ModelFamily = ModelFamily.Codex
    override val runtime: RuntimeKind = RuntimeKind.Codex

    data class SessionEvent(
        val sessionId: String?,
        val cwd: String?,
        val record: ProviderTelemetry.Codex,
        val unidentifiable: Boolean = false,
    )

    class SessionCursor(private val fallbackModel: String? = null) {
        var sessionId: String? = null
            private set
        var startedAt: Long? = null
            private set
        var forkedFromId: String? = null
            private set
        var cwd: String? = null
            private set
        var subagent: Boolean = false
            private set
        var currentModel: String? = fallbackModel
            private set
        var currentEffort: String? = null
            private set
        var latestRateLimits: RateLimits? = null
            private set
        var unidentifiableRequests: Int = 0
            private set

        private var previousTotal: AgentUsage? = null
        private var previousCounter: String? = null
        private var previousLastAtAndKey: Pair<Long?, String>? = null
        private var counterEpoch: Int = 0

        fun ingest(record: JsonObject): SessionEvent? {
            val at = parseIsoEpochMillis(record.str("timestamp"))
            when (record.str("type")) {
                "session_meta" -> {
                    val meta = record.obj("payload") ?: return null
                    sessionId = meta.str("id") ?: sessionId
                    startedAt = parseIsoEpochMillis(meta.str("timestamp")) ?: startedAt
                    forkedFromId = meta.str("forked_from_id") ?: forkedFromId
                    cwd = meta.str("cwd") ?: cwd
                    val source = meta["source"]
                    subagent = source is JsonObject && source["subagent"] != null
                    return null
                }
                "turn_context" -> {
                    val payload = record.obj("payload") ?: return null
                    currentModel = payload.str("model") ?: currentModel
                    currentEffort = payload.str("effort")
                        ?: payload.obj("collaboration_mode")?.obj("settings")?.str("reasoning_effort")
                        ?: currentEffort
                    cwd = payload.str("cwd") ?: cwd
                    return null
                }
                "compacted" -> {
                    val compacted = ProviderTelemetry.Codex(
                        timestampMillis = at,
                        model = currentModel,
                        effort = currentEffort,
                        subagent = subagent,
                        compacted = true,
                    )
                    return SessionEvent(sessionId, cwd, compacted)
                }
                "event_msg" -> {
                    val payload = record.obj("payload") ?: return null
                    when (payload.str("type")) {
                        "context_compacted" -> {
                            val compacted = ProviderTelemetry.Codex(
                                timestampMillis = at,
                                model = currentModel,
                                effort = currentEffort,
                                subagent = subagent,
                                compacted = true,
                            )
                            return SessionEvent(sessionId, cwd, compacted)
                        }
                        "token_count" -> {
                            parseRateLimitsFromPayload(payload, at)?.let { limits ->
                                if (latestRateLimits?.at == null || (at != null && at >= (latestRateLimits?.at ?: Long.MIN_VALUE))) {
                                    latestRateLimits = limits
                                }
                            }
                            val info = payload.obj("info") ?: return null
                            val totalObj = info.obj("total_token_usage")
                            val lastObj = info.obj("last_token_usage")
                            val total = totalObj?.let { parseTokenBreakdown(it, UsageScope.ProviderSessionTotal) }
                            val last = lastObj?.let { parseTokenBreakdown(it, UsageScope.Turn) }
                            if (total == null && last == null) return null
                            val reset = counterReset(previousTotal, total)
                            if (reset) counterEpoch++
                            val counter = total?.let {
                                listOf(it.inputTokens, it.outputTokens, it.cachedInputTokens, it.cacheWriteInputTokens, it.reasoningOutputTokens)
                                    .joinToString(":")
                            }
                            val lastKey = last?.let {
                                listOf(it.inputTokens, it.outputTokens, it.cachedInputTokens, it.cacheWriteInputTokens, it.reasoningOutputTokens)
                                    .joinToString(":")
                            }
                            val duplicate = (counter != null && counter == previousCounter && !reset) ||
                                (counter == null && lastKey != null && previousLastAtAndKey == (at to lastKey))
                            val freshSession = previousTotal == null && forkedFromId == null
                            val deltaUsage = last ?: total!!.forTurn(previousTotal, freshSession = freshSession)
                            previousTotal = total ?: previousTotal
                            previousCounter = counter
                            previousLastAtAndKey = lastKey?.let { at to it }
                            if (duplicate) return null
                            val id = sessionId
                            if (id == null || at == null) {
                                unidentifiableRequests++
                                val fallbackContext = last?.inputTokens ?: deltaUsage.inputTokens
                                val event = ProviderTelemetry.Codex(
                                    timestampMillis = at,
                                    model = currentModel,
                                    effort = currentEffort,
                                    inclusiveInputTokens = deltaUsage.inputTokens,
                                    cachedInputTokens = deltaUsage.cachedInputTokens,
                                    cacheWriteInputTokens = deltaUsage.cacheWriteInputTokens,
                                    outputTokens = deltaUsage.outputTokens,
                                    reasoningOutputTokens = deltaUsage.reasoningOutputTokens,
                                    totalTokens = lastObj?.nonNegativeLong("total_tokens") ?: totalObj?.nonNegativeLong("total_tokens"),
                                    contextTokens = fallbackContext,
                                    rateLimits = latestRateLimits,
                                    creditBalance = payload.obj("rate_limits")?.obj("credits")?.nonNegativeDouble("balance"),
                                    subagent = subagent,
                                    scope = UsageScope.Turn,
                                )
                                return SessionEvent(id, cwd, event, unidentifiable = true)
                            }
                            if (startedAt != null && at < startedAt!!) return null
                            val requestId = "$id:$counterEpoch:${counter ?: at}"
                            val context = last?.inputTokens ?: deltaUsage.inputTokens
                            val codex = ProviderTelemetry.Codex(
                                requestId = requestId,
                                timestampMillis = at,
                                model = currentModel,
                                effort = currentEffort,
                                inclusiveInputTokens = deltaUsage.inputTokens,
                                cachedInputTokens = deltaUsage.cachedInputTokens,
                                cacheWriteInputTokens = deltaUsage.cacheWriteInputTokens,
                                outputTokens = deltaUsage.outputTokens,
                                reasoningOutputTokens = deltaUsage.reasoningOutputTokens,
                                totalTokens = lastObj?.nonNegativeLong("total_tokens") ?: totalObj?.nonNegativeLong("total_tokens"),
                                contextTokens = context,
                                rateLimits = latestRateLimits,
                                creditBalance = payload.obj("rate_limits")?.obj("credits")?.nonNegativeDouble("balance"),
                                subagent = subagent,
                                scope = UsageScope.Turn,
                            )
                            return SessionEvent(id, cwd, codex)
                        }
                    }
                }
            }
            return null
        }

        private fun counterReset(previous: AgentUsage?, current: AgentUsage?): Boolean =
            previous != null && current != null && listOf(
                previous.inputTokens to current.inputTokens,
                previous.outputTokens to current.outputTokens,
                previous.cachedInputTokens to current.cachedInputTokens,
                previous.cacheWriteInputTokens to current.cacheWriteInputTokens,
                previous.reasoningOutputTokens to current.reasoningOutputTokens,
            ).any { (old, now) -> old != null && now != null && now < old }
    }

    fun parseTokenBreakdown(usage: JsonObject, scope: UsageScope = UsageScope.Turn): AgentUsage = AgentUsage(
        inputTokens = usage.nonNegativeLong("input_tokens")
            ?: usage.nonNegativeLong("inputTokens")
            ?: usage.nonNegativeLong("prompt_tokens"),
        cachedInputTokens = usage.nonNegativeLong("cached_input_tokens")
            ?: usage.nonNegativeLong("cachedInputTokens")
            ?: usage.obj("prompt_tokens_details")?.nonNegativeLong("cached_tokens"),
        cacheWriteInputTokens = usage.nonNegativeLong("cache_write_input_tokens")
            ?: usage.nonNegativeLong("cacheWriteInputTokens"),
        outputTokens = usage.nonNegativeLong("output_tokens")
            ?: usage.nonNegativeLong("outputTokens")
            ?: usage.nonNegativeLong("completion_tokens"),
        reasoningOutputTokens = usage.nonNegativeLong("reasoning_output_tokens")
            ?: usage.nonNegativeLong("reasoningOutputTokens")
            ?: usage.obj("output_tokens_details")?.nonNegativeLong("reasoning_tokens")
            ?: usage.obj("completion_tokens_details")?.nonNegativeLong("reasoning_tokens"),
        scope = scope,
    )

    fun parseAppServerTokenUsage(tokenUsage: JsonObject, model: String? = null, effort: String? = null): ProviderTelemetry.Codex? {
        val total = tokenUsage.obj("total")
        val last = tokenUsage.obj("last")
        val chosen = total ?: last ?: return null
        val breakdown = parseTokenBreakdown(chosen, UsageScope.ProviderSessionTotal)
        val lastContext = last?.let { parseTokenBreakdown(it, UsageScope.Turn).inputTokens }
        return ProviderTelemetry.Codex(
            model = model,
            effort = effort,
            inclusiveInputTokens = breakdown.inputTokens,
            cachedInputTokens = breakdown.cachedInputTokens,
            cacheWriteInputTokens = breakdown.cacheWriteInputTokens,
            outputTokens = breakdown.outputTokens,
            reasoningOutputTokens = breakdown.reasoningOutputTokens,
            totalTokens = chosen.nonNegativeLong("totalTokens") ?: chosen.nonNegativeLong("total_tokens"),
            contextTokens = lastContext ?: breakdown.inputTokens,
            scope = UsageScope.ProviderSessionTotal,
        )
    }

    override fun parseRecord(record: JsonObject, fallbackModel: String?, subagent: Boolean): ProviderTelemetry? {
        val at = parseIsoEpochMillis(record.str("timestamp"))
        return when (record.str("type")) {
            "turn.completed" -> record.obj("usage")?.let {
                parseApiUsage(it, fallbackModel, UsageScope.Turn)
                    .let { parsed -> (parsed as ProviderTelemetry.Codex).copy(timestampMillis = at, subagent = subagent) }
            }
            "compacted" -> ProviderTelemetry.Codex(timestampMillis = at, model = fallbackModel, subagent = subagent, compacted = true)
            "event_msg" -> {
                val payload = record.obj("payload") ?: return null
                when (payload.str("type")) {
                    "context_compacted" -> ProviderTelemetry.Codex(timestampMillis = at, model = fallbackModel, subagent = subagent, compacted = true)
                    "token_count" -> {
                        val info = payload.obj("info")
                        val last = info?.obj("last_token_usage")
                        val total = info?.obj("total_token_usage")
                        val chosen = last ?: total ?: return null
                        val scope = if (last != null) UsageScope.Turn else UsageScope.ProviderSessionTotal
                        val breakdown = parseTokenBreakdown(chosen, scope)
                        ProviderTelemetry.Codex(
                            timestampMillis = at,
                            model = fallbackModel,
                            inclusiveInputTokens = breakdown.inputTokens,
                            cachedInputTokens = breakdown.cachedInputTokens,
                            cacheWriteInputTokens = breakdown.cacheWriteInputTokens,
                            outputTokens = breakdown.outputTokens,
                            reasoningOutputTokens = breakdown.reasoningOutputTokens,
                            totalTokens = chosen.nonNegativeLong("total_tokens"),
                            contextTokens = last?.nonNegativeLong("input_tokens") ?: breakdown.inputTokens,
                            rateLimits = parseRateLimitsFromPayload(payload, at),
                            creditBalance = payload.obj("rate_limits")?.obj("credits")?.nonNegativeDouble("balance"),
                            subagent = subagent,
                            scope = scope,
                        )
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    override fun parseApiUsage(
        usage: JsonObject,
        model: String?,
        scope: UsageScope,
        costUsd: Double?,
        durationMillis: Long?,
    ): ProviderTelemetry.Codex {
        val breakdown = parseTokenBreakdown(usage, scope)
        return ProviderTelemetry.Codex(
            model = model,
            inclusiveInputTokens = breakdown.inputTokens,
            cachedInputTokens = breakdown.cachedInputTokens,
            cacheWriteInputTokens = breakdown.cacheWriteInputTokens,
            outputTokens = breakdown.outputTokens,
            reasoningOutputTokens = breakdown.reasoningOutputTokens,
            totalTokens = usage.nonNegativeLong("total_tokens") ?: usage.nonNegativeLong("totalTokens"),
            contextTokens = breakdown.inputTokens,
            durationMillis = durationMillis,
            costUsd = costUsd?.takeIf { it.isFinite() && it >= 0.0 },
            scope = scope,
        )
    }

    override fun parseRateLimits(record: JsonObject, fallbackObservedAt: Long?): RateLimits? {
        val at = parseIsoEpochMillis(record.str("timestamp")) ?: fallbackObservedAt
        if (record.str("type") == "event_msg") {
            val payload = record.obj("payload") ?: return null
            if (payload.str("type") == "token_count") return parseRateLimitsFromPayload(payload, at)
        }
        val direct = record.obj("rate_limits") ?: record.obj("rateLimits") ?: record
        return parseRateLimitsObject(direct, at)
    }

    private fun parseRateLimitsFromPayload(payload: JsonObject, observedAt: Long?): RateLimits? =
        (payload.obj("rate_limits") ?: payload.obj("rateLimits"))?.let { parseRateLimitsObject(it, observedAt) }

    fun parseRateLimitsObject(limits: JsonObject, observedAt: Long? = null): RateLimits? {
        fun window(key: String): RateWindow? {
            val raw = limits.obj(key) ?: return null
            val used = raw.nonNegativeDouble("used_percent") ?: raw.nonNegativeDouble("usedPercent") ?: return null
            if (used !in 0.0..100.0) return null
            val minutes = raw.nonNegativeLong("window_minutes") ?: raw.nonNegativeLong("windowMinutes")
            val resetRaw = raw.nonNegativeLong("resets_at") ?: raw.nonNegativeLong("resetsAt")
            val resetMillis = resetRaw?.let { if (it < 100_000_000_000L) it * 1000L else it }
            return RateWindow(usedPercent = used, minutes = minutes, resetsAt = resetMillis)
        }
        val windows = listOfNotNull(window("primary"), window("secondary"))
        val plan = limits.str("plan_type") ?: limits.str("planType")
        if (windows.isEmpty()) return null
        return RateLimits(
            windows = windows,
            plan = plan,
            at = observedAt,
        )
    }

    @Serializable
    data class WeeklyAllowance(
        val limitId: String,
        val usedPercent: Double,
        val resetsAtEpochSeconds: Long,
        val observedAtEpochMillis: Long,
    )

    fun parseWeeklyAllowances(record: JsonObject): List<WeeklyAllowance> {
        if (record.str("type") != "event_msg") return emptyList()
        val payload = record.obj("payload") ?: return emptyList()
        if (payload.str("type") != "token_count") return emptyList()
        val at = parseIsoEpochMillis(record.str("timestamp")) ?: return emptyList()
        val limits = payload.obj("rate_limits") ?: return emptyList()
        val id = limits.str("limit_id") ?: "codex"
        return listOf("primary", "secondary").mapNotNull { slot ->
            val window = limits.obj(slot) ?: return@mapNotNull null
            if (window.nonNegativeLong("window_minutes") != 10080L) return@mapNotNull null
            val reset = window.nonNegativeLong("resets_at") ?: return@mapNotNull null
            val used = window.nonNegativeDouble("used_percent")?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
            WeeklyAllowance(limitId = id, usedPercent = used, resetsAtEpochSeconds = reset, observedAtEpochMillis = at)
        }
    }
}

data object GeminiTelemetry : AgentTelemetry {
    override val family: ModelFamily = ModelFamily.Gemini
    override val runtime: RuntimeKind = RuntimeKind.Gemini

    private val pathArgumentKeys = listOf(
        "Cwd", "DirectoryPath", "SearchDirectory", "TargetFile", "AbsolutePath", "Path", "File",
    )

    class SessionCursor(
        private val conversationId: String? = null,
        initialModel: String? = null,
        private val subagent: Boolean = false,
    ) {
        var model: String? = initialModel
            private set
        private val steps = linkedMapOf<Int, ProviderTelemetry.Gemini>()
        private val anonymous = mutableListOf<ProviderTelemetry.Gemini>()
        val childConversationIds = linkedSetOf<String>()
        val observedPaths = linkedSetOf<String>()

        fun ingest(record: JsonObject): ProviderTelemetry.Gemini? {
            if (record.str("event") == "init") {
                model = record.obj("init")?.str("model") ?: model
                return null
            }
            extractMetadata(record)
            val parsed = parseRecord(record, model, subagent) as? ProviderTelemetry.Gemini ?: return null
            val withId = if (parsed.requestId == null && conversationId != null && parsed.stepIndex != null) {
                parsed.copy(requestId = "$conversationId:${parsed.stepIndex}")
            } else parsed
            val step = withId.stepIndex
            if (step != null) {
                val prior = steps[step]
                val merged = if (prior == null) withId else mergeStep(prior, withId)
                steps[step] = merged
                return merged
            }
            anonymous += withId
            return withId
        }

        fun records(): List<ProviderTelemetry.Gemini> = steps.values + anonymous

        private fun extractMetadata(record: JsonObject) {
            val calls = record["tool_calls"] as? JsonArray ?: return
            for (element in calls) {
                val call = element as? JsonObject ?: continue
                val name = call.str("name")
                val args = call.obj("args")
                if (args != null) {
                    for (key in pathArgumentKeys) {
                        args.str(key)?.takeIf { it.startsWith("/") }?.let(observedPaths::add)
                    }
                }
                if (name == "invoke_subagent") {
                    extractUuidCandidates(call["result"] ?: call["output"])
                        .forEach(childConversationIds::add)
                }
            }
        }
    }

    fun extractSubagentIdsFromStepResult(record: JsonObject): Set<String> {
        val text = record.str("content") ?: return emptySet()
        if (!text.contains("conversationId", ignoreCase = true) && !text.contains("subagent", ignoreCase = true)) {
            return emptySet()
        }
        return extractUuidCandidates(JsonPrimitive(text))
    }

    private fun mergeStep(first: ProviderTelemetry.Gemini, second: ProviderTelemetry.Gemini): ProviderTelemetry.Gemini =
        ProviderTelemetry.Gemini(
            requestId = second.requestId ?: first.requestId,
            timestampMillis = minMillis(first.timestampMillis, second.timestampMillis),
            model = second.model ?: first.model,
            effort = second.effort ?: first.effort,
            stepIndex = first.stepIndex ?: second.stepIndex,
            freshInputTokens = maxCount(first.freshInputTokens, second.freshInputTokens),
            inclusiveInputTokens = maxCount(first.inclusiveInputTokens, second.inclusiveInputTokens),
            cacheReadTokens = maxCount(first.cacheReadTokens, second.cacheReadTokens),
            cacheWriteTokens = maxCount(first.cacheWriteTokens, second.cacheWriteTokens),
            outputTokens = maxCount(first.outputTokens, second.outputTokens),
            thinkingTokens = maxCount(first.thinkingTokens, second.thinkingTokens),
            durationMillis = maxCount(first.durationMillis, second.durationMillis),
            costUsd = maxDouble(first.costUsd, second.costUsd),
            truncatedFields = (first.truncatedFields + second.truncatedFields).distinct(),
            rateLimits = second.rateLimits ?: first.rateLimits,
            subagent = first.subagent || second.subagent,
            compacted = first.compacted || second.compacted,
            scope = second.scope,
        )

    override fun parseRecord(record: JsonObject, fallbackModel: String?, subagent: Boolean): ProviderTelemetry? {
        if (record.str("event") == "result") {
            val result = record.obj("result") ?: return null
            val usage = result.obj("usage") ?: return null
            val parsed = parseApiUsage(usage, fallbackModel, UsageScope.ProviderSessionTotal) as ProviderTelemetry.Gemini
            return parsed.copy(subagent = subagent)
        }
        record.obj("usageMetadata")?.let { metadata ->
            val parsed = parseApiUsage(metadata, record.str("modelVersion") ?: fallbackModel, UsageScope.Turn) as ProviderTelemetry.Gemini
            return parsed.copy(subagent = subagent)
        }
        val at = parseIsoEpochMillis(record.str("created_at") ?: record.str("timestamp"))
        val step = record.int("step_index")
        return when (record.str("type")) {
            "PLANNER_RESPONSE" -> {
                val fresh = record.nonNegativeLong("input_tokens")
                val cached = record.nonNegativeLong("cache_read_tokens")
                val written = record.nonNegativeLong("cache_write_tokens") ?: record.nonNegativeLong("cache_creation_tokens")
                val output = record.nonNegativeLong("output_tokens")
                val thinking = record.nonNegativeLong("thinking_tokens") ?: record.nonNegativeLong("thoughts_token_count")
                val truncated = (record["truncated_fields"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                ProviderTelemetry.Gemini(
                    requestId = step?.let { "step:$it" },
                    timestampMillis = at,
                    model = record.str("model") ?: fallbackModel,
                    stepIndex = step,
                    freshInputTokens = fresh,
                    cacheReadTokens = cached,
                    cacheWriteTokens = written,
                    outputTokens = output,
                    thinkingTokens = thinking,
                    truncatedFields = truncated,
                    subagent = subagent,
                    scope = UsageScope.Turn,
                )
            }
            "CONTEXT_COMPACTION" -> ProviderTelemetry.Gemini(
                requestId = step?.let { "compact:$it" },
                timestampMillis = at,
                model = record.str("model") ?: fallbackModel,
                stepIndex = step,
                subagent = subagent,
                compacted = true,
            )
            else -> null
        }
    }

    override fun parseApiUsage(
        usage: JsonObject,
        model: String?,
        scope: UsageScope,
        costUsd: Double?,
        durationMillis: Long?,
    ): ProviderTelemetry.Gemini {
        val restInclusive = usage.nonNegativeLong("promptTokenCount") ?: usage.nonNegativeLong("prompt_token_count")
        val fresh = if (restInclusive == null) usage.nonNegativeLong("input_tokens") else null
        val cached = usage.nonNegativeLong("cache_read_tokens")
            ?: usage.nonNegativeLong("cachedContentTokenCount")
            ?: usage.nonNegativeLong("cached_content_token_count")
        val written = usage.nonNegativeLong("cache_write_tokens")
        val output = usage.nonNegativeLong("output_tokens")
            ?: usage.nonNegativeLong("candidatesTokenCount")
            ?: usage.nonNegativeLong("candidates_token_count")
        val thinking = usage.nonNegativeLong("thinking_tokens")
            ?: usage.nonNegativeLong("thoughtsTokenCount")
            ?: usage.nonNegativeLong("thoughts_token_count")
        return ProviderTelemetry.Gemini(
            model = model,
            freshInputTokens = fresh,
            inclusiveInputTokens = restInclusive,
            cacheReadTokens = cached,
            cacheWriteTokens = written,
            outputTokens = output,
            thinkingTokens = thinking,
            durationMillis = durationMillis,
            costUsd = costUsd?.takeIf { it.isFinite() && it >= 0.0 },
            scope = scope,
        )
    }

    override fun parseRateLimits(record: JsonObject, fallbackObservedAt: Long?): RateLimits? = null
}

fun parseIsoEpochMillis(raw: String?): Long? {
    val text = raw?.trim()?.takeIf { it.length >= 20 } ?: return null
    val tIndex = text.indexOfFirst { it == 'T' || it == 't' || it == ' ' }
    if (tIndex < 10) return null
    val datePart = text.substring(0, tIndex)
    val datePieces = datePart.split('-')
    if (datePieces.size != 3) return null
    val year = datePieces[0].toIntOrNull() ?: return null
    val month = datePieces[1].toIntOrNull() ?: return null
    val day = datePieces[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null

    val rest = text.substring(tIndex + 1)
    val zoneIndex = when {
        rest.endsWith("Z") || rest.endsWith("z") -> rest.length - 1
        else -> {
            val plus = rest.lastIndexOf('+')
            val minus = rest.lastIndexOf('-')
            maxOf(plus, minus).takeIf { it >= 8 } ?: return null
        }
    }
    val timePart = rest.substring(0, zoneIndex)
    val zonePart = rest.substring(zoneIndex)

    val dot = timePart.indexOf('.')
    val clockPart = if (dot >= 0) timePart.substring(0, dot) else timePart
    val fracPart = if (dot >= 0) timePart.substring(dot + 1) else ""
    val clockPieces = clockPart.split(':')
    if (clockPieces.size != 3) return null
    val hour = clockPieces[0].toIntOrNull() ?: return null
    val minute = clockPieces[1].toIntOrNull() ?: return null
    val second = clockPieces[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    if (fracPart.any { !it.isDigit() }) return null
    val millis = if (fracPart.isEmpty()) 0L else fracPart.take(3).padEnd(3, '0').toLongOrNull() ?: return null

    val offsetSeconds = when {
        zonePart == "Z" || zonePart == "z" -> 0L
        else -> {
            val sign = if (zonePart[0] == '-') -1L else 1L
            val digits = zonePart.substring(1).replace(":", "")
            if (digits.length != 4 && digits.length != 2) return null
            val zh = digits.substring(0, 2).toIntOrNull() ?: return null
            val zm = if (digits.length == 4) digits.substring(2, 4).toIntOrNull() ?: return null else 0
            if (zh !in 0..23 || zm !in 0..59) return null
            sign * (zh * 3600L + zm * 60L)
        }
    }

    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = if (adjustedYear >= 0) adjustedYear / 400 else (adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val daysSinceEpoch = era * 146097L + dayOfEra - 719468L
    val utcSeconds = daysSinceEpoch * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
    return utcSeconds * 1000L + millis
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    else -> 0
}

private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun extractUuidCandidates(element: JsonElement?): Set<String> {
    if (element == null) return emptySet()
    return uuidPattern.findAll(element.toString()).map { it.value.lowercase() }.toSet()
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.nonNegativeLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }
private fun JsonObject.nonNegativeDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }

private fun maxCount(first: Long?, second: Long?): Long? = listOfNotNull(first, second).maxOrNull()
private fun maxDouble(first: Double?, second: Double?): Double? =
    listOfNotNull(first, second).filter { it.isFinite() && it >= 0.0 }.maxOrNull()
private fun minMillis(first: Long?, second: Long?): Long? = listOfNotNull(first, second).minOrNull()
private fun deltaCount(next: Long?, previous: Long?): Long? = next?.minus(previous ?: 0L)
private fun deltaDouble(next: Double?, previous: Double?): Double? = next?.minus(previous ?: 0.0)
