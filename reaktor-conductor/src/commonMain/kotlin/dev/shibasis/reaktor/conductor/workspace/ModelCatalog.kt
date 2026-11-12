package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.Serializable

@Serializable
data class CatalogSource(val name: String, val url: String, val readOn: String)

@Serializable
data class CatalogRoute(val runtime: RuntimeKind, val pool: String, val needs: String? = null)

@Serializable
data class CatalogVariant(
    val effort: String,
    val model: String,
    val intelligence: Int? = null,
    val costPerTask: Double? = null,
    val minutesPerTask: Double? = null,
    val firstAnswerSeconds: Double? = null,
    val codingAgent: Map<String, Double> = emptyMap(),
)

@Serializable
data class CatalogPricing(
    val source: CatalogSource,
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cachedInputPerMillion: Double,
    val cacheWrite5mPerMillion: Double,
    val cacheWrite1hPerMillion: Double,
    val longContextAbove: Int? = null,
    val longContextMultiplier: Double = 1.0,
)

@Serializable
data class CatalogModel(
    val key: String,
    val family: ModelFamily,
    val name: String,
    val routes: List<CatalogRoute>,
    val variants: List<CatalogVariant>,
    val pricing: CatalogPricing? = null,
) {
    val scored: Boolean get() = variants.any { it.intelligence != null }

    fun variant(effort: String?): CatalogVariant? = variants.firstOrNull { it.effort == effort }
}

data class CatalogPick(val model: CatalogModel, val variant: CatalogVariant) {
    val key: String get() = "${model.key}:${variant.effort}"
    val label: String get() = "${model.name} · ${variant.effort}"
    val runtime: RuntimeKind get() = model.routes.first().runtime
}

enum class CatalogLens(val title: String) { Intelligence("Intelligence Index"), CodingAgent("Coding Agent Index") }

data class CatalogPoint(val pick: CatalogPick, val score: Double, val harness: String? = null) {
    val key: String get() = harness?.let { "${pick.key}@$it" } ?: pick.key
}

object ModelCatalog {
    val intelligence = CatalogSource("Artificial Analysis model leaderboard", "https://artificialanalysis.ai/leaderboards/models", "11 Nov 2026")
    val codingAgents = CatalogSource("Artificial Analysis Coding Agent Index, captured by benchlm.ai", "https://benchlm.ai/benchmarks/aacodingagents", "10 Nov 2026")

    val claudePricing = CatalogSource("Anthropic API pricing", "https://platform.claude.com/docs/en/about-claude/pricing", "12 Nov 2026")

    private val claude = listOf(CatalogRoute(RuntimeKind.ClaudeCode, "Claude plan"), CatalogRoute(RuntimeKind.Gateway, "AI Gateway · Anthropic API", "an Anthropic key"))
    private val gpt = listOf(CatalogRoute(RuntimeKind.Codex, "ChatGPT plan"), CatalogRoute(RuntimeKind.Gateway, "AI Gateway · OpenAI API", "an OpenAI key"))
    private val gemini = listOf(CatalogRoute(RuntimeKind.Gemini, "Google plan"), CatalogRoute(RuntimeKind.Gateway, "AI Gateway · Gemini API", "a Gemini key"))
    private val deepseek = listOf(CatalogRoute(RuntimeKind.ClaudeCode, "DeepSeek API", "DEEPSEEK_API_KEY"), CatalogRoute(RuntimeKind.Gateway, "AI Gateway · DeepSeek", "a DeepSeek key"))
    private val kimi = listOf(CatalogRoute(RuntimeKind.ClaudeCode, "Moonshot API", "MOONSHOT_API_KEY"), CatalogRoute(RuntimeKind.Gateway, "AI Gateway · Moonshot", "a Moonshot key"))
    private val sarvam = listOf(CatalogRoute(RuntimeKind.Gateway, "AI Gateway · Sarvam", "a Sarvam key"))

    private fun v(effort: String, model: String, index: Int?, cost: Double?, minutes: Double?, first: Double?, vararg coding: Pair<String, Double>) =
        CatalogVariant(effort, model, index, cost, minutes, first, coding.toMap())

    val models: List<CatalogModel> = listOf(
        CatalogModel("claude-fable-5-2", ModelFamily.Claude, "Fable 5.2", claude, listOf(v("max", "claude-fable-5-2", 65, 26.54, 45.0, 247.85), v("xhigh", "claude-fable-5-2", 62, 14.83, 25.8, 69.21),
            v("high", "claude-fable-5-2", 62, 9.08, 16.9, 30.02), v("medium", "claude-fable-5-2", 56, 3.80, 7.7, 12.88),
            v("low", "claude-fable-5-2", 54, 3.10, 6.3, 8.14)), pricing = CatalogPricing(claudePricing, 8.0, 40.0, 0.20, 10.0, 16.0)),
        CatalogModel("claude-opus-5-5", ModelFamily.Claude, "Opus 5.5", claude, listOf(v("max", "claude-opus-5-5", 59, 10.04, 25.0, 353.75, "Claude Code" to 61.7), v("xhigh", "claude-opus-5-5", 57, 6.05, 15.7, 81.58),
            v("high", "claude-opus-5-5", 52, 3.46, 9.5, 25.14), v("medium", "claude-opus-5-5", 48, 2.45, 7.1, 14.88),
            v("low", "claude-opus-5-5", 35, 0.98, 2.9, 5.21)), pricing = CatalogPricing(claudePricing, 4.0, 20.0, 0.20, 5.0, 8.0)),
        CatalogModel("claude-sonnet-5-5", ModelFamily.Claude, "Sonnet 5.5", claude, listOf(v("max", "claude-sonnet-5-5", 55, 10.02, 32.7, 254.92, "Claude Code" to 59.3), v("xhigh", "claude-sonnet-5-5", 50, 4.09, 14.6, 27.88),
            v("high", "claude-sonnet-5-5", 42, 1.64, 6.9, 10.07), v("medium", "claude-sonnet-5-5", 31, 0.78, 3.7, 5.00),
            v("low", "claude-sonnet-5-5", 26, 0.51, 2.5, 2.82)), pricing = CatalogPricing(claudePricing, 2.0, 10.0, 0.10, 2.5, 4.0)),
        CatalogModel("claude-haiku-5-5", ModelFamily.Claude, "Haiku 5.5", claude, listOf(v("max", "claude-haiku-5-5", 36, 0.41, 14.8, 185.68, "Claude Code" to 37.3), v("xhigh", "claude-haiku-5-5", 35, 0.25, 9.6, 57.64),
            v("high", "claude-haiku-5-5", 30, 0.16, 6.4, 21.41), v("medium", "claude-haiku-5-5", 25, 0.09, 4.0, 11.63),
            v("low", "claude-haiku-5-5", 19, 0.04, 2.1, 7.26)), pricing = CatalogPricing(claudePricing, 0.10, 0.50, 0.01, 0.125, 0.20, longContextAbove = 100_000, longContextMultiplier = 5.0)),
        CatalogModel("gpt-6-astra", ModelFamily.Codex, "Astra 6", gpt, listOf(v("max", "gpt-6-astra", 57, 6.24, 23.0, 249.02, "Codex" to 54.7), v("xhigh", "gpt-6-astra", 55, 3.66, 11.3, 113.66),
            v("high", "gpt-6-astra", 55, 3.24, 9.7, 66.07), v("medium", "gpt-6-astra", 52, 2.73, 7.0, 6.03),
            v("low", "gpt-6-astra", 43, 1.42, 3.8, 3.59))),
        CatalogModel("gpt-6.1-sol", ModelFamily.Codex, "Sol 6.1", gpt, listOf(v("max", "gpt-6.1-sol", 55, 1.27, 20.4, 156.46, "Codex" to 54.1), v("xhigh", "gpt-6.1-sol", 52, 0.76, 9.2, 64.94),
            v("high", "gpt-6.1-sol", 50, 0.59, 6.7, 35.47), v("medium", "gpt-6.1-sol", 46, 0.36, 3.9, 4.53),
            v("low", "gpt-6.1-sol", 35, 0.16, 1.8, 2.75))),
        CatalogModel("gpt-6.1-terra", ModelFamily.Codex, "Terra 6.1", gpt, listOf(v("max", "gpt-6.1-terra", 51, 0.96, 13.5, 157.72, "Codex" to 48.1), v("xhigh", "gpt-6.1-terra", 45, 0.40, 6.1, 29.04, "Codex" to 48.2),
            v("high", "gpt-6.1-terra", 42, 0.26, 4.0, 10.85, "Codex" to 46.3), v("medium", "gpt-6.1-terra", 38, 0.17, 2.6, 4.18, "Codex" to 42.0),
            v("low", "gpt-6.1-terra", 29, 0.07, 1.1, 2.78, "Codex" to 34.9), v("none", "gpt-6.1-terra", 31, 0.23, 2.3, 2.15, "Codex" to 39.2))),
        CatalogModel("gpt-6-luna", ModelFamily.Codex, "Luna 6", gpt, listOf(v("max", "gpt-6-luna", 28, 0.15, 11.5, 81.37, "Codex" to 31.2), v("xhigh", "gpt-6-luna", 23, 0.10, 7.1, 20.48),
            v("high", "gpt-6-luna", 21, 0.07, 5.7, 12.79), v("medium", "gpt-6-luna", 18, 0.04, 3.3, 6.19),
            v("low", "gpt-6-luna", 10, 0.01, 0.6, 3.39), v("none", "gpt-6-luna", 9, 0.07, 1.9, 1.60))),
        CatalogModel("gemini-4-carbon", ModelFamily.Gemini, "Gemini 4 Carbon", gemini, listOf(v("high", "gemini-4-carbon-high", 60, 7.76, 26.1, 66.01, "Antigravity" to 59.4), v("medium", "gemini-4-carbon-medium", 58, 6.59, 23.8, 23.21),
            v("low", "gemini-4-carbon-low", 54, 7.19, 23.3, 12.24))),
        CatalogModel("gemini-4-argon", ModelFamily.Gemini, "Gemini 4 Argon", gemini, listOf(v("high", "gemini-4-argon-high", 50, 9.95, 29.6, 39.35, "Antigravity" to 53.2))),
        CatalogModel("deepseek-v4.1-flash", ModelFamily.DeepSeek, "DeepSeek V4.1 Flash", deepseek, listOf(v("max", "deepseek-v4.1-flash", 33, 0.60, 16.3, 49.42, "Codex" to 47.3, "DeepSeek harness" to 46.3), v("high", "deepseek-v4.1-flash", 29, 0.46, 12.6, 23.67),
            v("none", "deepseek-v4.1-flash", 19, 0.43, 8.3, 1.33))),
        CatalogModel("deepseek-v4-pro", ModelFamily.DeepSeek, "DeepSeek V4 Pro", deepseek, listOf(v("max", "deepseek-v4-pro", 23, 1.77, 23.4, 63.61), v("none", "deepseek-v4-pro", 13, 0.98, 17.7, 3.37))),
        CatalogModel("kimi-k3.1-pro", ModelFamily.Kimi, "Kimi K3.1 Pro", kimi, listOf(v("default", "kimi-k3.1-pro", 46, 3.68, 37.3, 169.69))),
        CatalogModel("kimi-k3", ModelFamily.Kimi, "Kimi K3", kimi, listOf(v("max", "kimi-k3", null, null, null, null, "Kimi Code CLI" to 41.4))),
        CatalogModel("sarvam-105b", ModelFamily.Sarvam, "Sarvam 105B", sarvam, listOf(v("high", "sarvam-105b", null, null, null, 8.20))),
        CatalogModel("sarvam-30b", ModelFamily.Sarvam, "Sarvam 30B", sarvam, listOf(v("high", "sarvam-30b", null, null, null, null))),
    )

    val picks: List<CatalogPick> by lazy { models.flatMap { model -> model.variants.map { CatalogPick(model, it) } } }

    fun points(lens: CatalogLens): List<CatalogPoint> = when (lens) {
        CatalogLens.Intelligence -> picks.mapNotNull { pick -> pick.variant.intelligence?.let { CatalogPoint(pick, it.toDouble()) } }
        CatalogLens.CodingAgent -> picks.flatMap { pick -> pick.variant.codingAgent.map { (harness, score) -> CatalogPoint(pick, score, harness) } }
    }

    fun model(key: String): CatalogModel? = models.firstOrNull { it.key == key }

    fun pick(key: String): CatalogPick? = picks.firstOrNull { it.key == key }

    fun find(runtime: RuntimeKind?, model: String?, effort: String?): CatalogPick? {
        val id = model?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        val matches = picks.filter { it.variant.model == id || it.model.key == id }
        return matches.firstOrNull { it.variant.model == id && it.variant.model != it.model.key }
            ?: matches.firstOrNull { it.variant.effort == (effort?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: "default") }
            ?: matches.firstOrNull { runtime == null || it.model.routes.any { route -> route.runtime == runtime } }
    }

    fun band(score: Double): Int = when {
        score >= FineFrom -> score.toInt()
        score >= CoarseFrom -> (score / BandWidth).toInt() * BandWidth
        score >= FloorFrom -> FloorFrom
        else -> 0
    }

    fun bandTop(floor: Int): Int = when {
        floor >= FineFrom -> floor
        floor >= CoarseFrom -> floor + BandWidth - 1
        floor >= FloorFrom -> CoarseFrom - 1
        else -> FloorFrom - 1
    }

    const val BandWidth = 5
    const val FineFrom = 50
    const val CoarseFrom = 40
    const val FloorFrom = 30
}
