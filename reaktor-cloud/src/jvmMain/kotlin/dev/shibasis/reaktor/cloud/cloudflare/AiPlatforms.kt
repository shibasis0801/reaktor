package dev.shibasis.reaktor.cloud.cloudflare

import dev.shibasis.reaktor.cloud.http.Transport
import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

@Serializable
data class AiAllowanceWindow(
    val label: String,
    val usedPercent: Double? = null,
    val remaining: String? = null,
    val limit: String? = null,
    val resetsAtMillis: Long? = null,
)

@Serializable
data class AiCreditBalance(
    val status: String,
    val availableUsd: Double? = null,
    val used7dUsd: Double? = null,
    val limitUsd: Double? = null,
    val neurons7d: Double? = null,
    val planTier: String? = null,
    val summary: String,
)

@Serializable
data class AiPlatformSnapshot(
    val id: String,
    val name: String,
    val provider: String,
    val connected: Boolean,
    val authSource: String,
    val accountOrOrg: String? = null,
    val baseUrl: String,
    val dashboardUrl: String,
    val modelCount: Int = 0,
    val endpointCount: Int = 0,
    val requests7d: Long = 0,
    val inputTokens7d: Long = 0,
    val cachedTokens7d: Long = 0,
    val outputTokens7d: Long = 0,
    val reasoningTokens7d: Long = 0,
    val credits: AiCreditBalance,
    val allowances: List<AiAllowanceWindow> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
)

@Serializable
data class AiApiEndpoint(
    val id: String,
    val platform: String,
    val name: String,
    val method: String,
    val path: String,
    val category: String,
    val authStatus: String,
    val available: Boolean,
    val rateLimitSummary: String? = null,
    val requests7d: Long = 0,
    val summary: String,
    val docsUrl: String,
)

@Serializable
data class AiPlatformModel(
    val id: String,
    val provider: String,
    val name: String,
    val task: String,
    val description: String,
    val functionCalling: Boolean = false,
    val reasoning: Boolean = false,
    val vision: Boolean = false,
    val contextWindow: Long? = null,
    val inputUsdPerMillion: Double? = null,
    val outputUsdPerMillion: Double? = null,
    val cachedInputUsdPerMillion: Double? = null,
    val requests7d: Long = 0,
    val inputTokens7d: Long = 0,
    val outputTokens7d: Long = 0,
    val cost7dUsd: Double = 0.0,
    val source: String = "catalog",
)

object AiPlatforms {
    private val http = Transport.Shared

    suspend fun enrich(root: File, base: AiReading, days: Int = 7): AiReading = coroutineScope {
        val cfDeferred = async { buildCloudflare(base) }
        val openAiDeferred = async { buildOpenAi(root, base, days) }
        val claudeDeferred = async { buildAnthropic(root, base, days) }
        val geminiDeferred = async { buildGemini(root, base, days) }

        val cf = cfDeferred.await()
        val openAi = openAiDeferred.await()
        val claude = claudeDeferred.await()
        val gemini = geminiDeferred.await()

        val allPlatforms = listOf(cf.snapshot, openAi.snapshot, claude.snapshot, gemini.snapshot)
        val allEndpoints = cf.endpoints + openAi.endpoints + claude.endpoints + gemini.endpoints
        val allModels = cf.models + openAi.models + claude.models + gemini.models

        base.copy(
            platforms = allPlatforms,
            endpoints = allEndpoints,
            platformModels = allModels,
        )
    }

    suspend fun attempt(root: File, ai: CloudflareAi?, call: CloudflareAiRequest): AiTry {
        val model = requireNotNull(call.model) { "Name a model" }
        val gateway = call.gateway
        val provider = providerForModel(model)
        val started = System.currentTimeMillis()
        if (provider == "cloudflare" && ai != null) {
            val request = buildJsonObject {
                put("model", model)
                put("max_tokens", 2048)
                put("messages", JsonArray(listOfNotNull(
                    call.system?.takeIf(String::isNotBlank)?.let { buildJsonObject { put("role", "system"); put("content", it) } },
                    buildJsonObject { put("role", "user"); put("content", call.prompt.orEmpty()) },
                )))
            }
            return runCatching { ai.complete(request, gateway, mapOf("source" to "hangar-try")) }.fold(
                onSuccess = { completion ->
                    val message = completion.body.objects("choices").firstOrNull().obj("message")
                    val usage = completion.body.obj("usage")
                    AiTry(
                        model = model,
                        gateway = gateway,
                        text = message.text("content").orEmpty(),
                        reasoning = message.text("reasoning_content"),
                        tokensIn = usage.long("prompt_tokens") ?: 0,
                        tokensOut = usage.long("completion_tokens") ?: 0,
                        neurons = usage.number("neurons"),
                        millis = completion.millis,
                        logId = completion.logId,
                    )
                },
                onFailure = { AiTry(model, gateway, "", null, 0, 0, null, 0, null, it.message ?: "The call failed") },
            )
        }

        if (gateway != null && ai != null) {
            val gatewayModel = if ('/' in model) model else "$provider/$model"
            val request = buildJsonObject {
                put("model", gatewayModel)
                put("max_tokens", 2048)
                put("messages", JsonArray(listOfNotNull(
                    call.system?.takeIf(String::isNotBlank)?.let { buildJsonObject { put("role", "system"); put("content", it) } },
                    buildJsonObject { put("role", "user"); put("content", call.prompt.orEmpty()) },
                )))
            }
            val gatewayResult = runCatching { ai.completeGateway(request, gateway) }.getOrNull()
            if (gatewayResult != null) {
                return AiTry(
                    model = model,
                    gateway = gateway,
                    text = gatewayResult.text,
                    reasoning = gatewayResult.reasoning,
                    tokensIn = gatewayResult.usage.inputTokens,
                    tokensOut = gatewayResult.usage.outputTokens,
                    neurons = gatewayResult.usage.neurons,
                    millis = gatewayResult.millis,
                    logId = gatewayResult.logId,
                )
            }
        }

        return when (provider) {
            "openai" -> tryOpenAiDirect(root, model.removePrefix("openai/"), call.system, call.prompt.orEmpty(), call.gateway, started)
            "anthropic" -> tryAnthropicDirect(root, model.removePrefix("anthropic/"), call.system, call.prompt.orEmpty(), call.gateway, started)
            "gemini" -> tryGeminiDirect(root, model.removePrefix("google-ai-studio/").removePrefix("gemini/"), call.system, call.prompt.orEmpty(), call.gateway, started)
            else -> AiTry(model, call.gateway, "", null, 0, 0, null, 0, null, "No Cloudflare account or provider route configured for $model")
        }
    }

    fun providerForModel(model: String): String {
        val clean = model.lowercase().trim()
        return when {
            clean.startsWith("@cf/") || clean.startsWith("@hf/") || clean.startsWith("workers-ai/") -> "cloudflare"
            clean.startsWith("openai/") || clean.startsWith("gpt-") || clean.startsWith("o1") || clean.startsWith("o3") || clean.startsWith("o4") || clean.startsWith("text-embedding-") || clean.startsWith("whisper") || clean.startsWith("tts-") || clean.startsWith("dall-e") || clean.startsWith("codex-") -> "openai"
            clean.startsWith("anthropic/") || clean.startsWith("claude-") -> "anthropic"
            clean.startsWith("google-ai-studio/") || clean.startsWith("vertex-ai/") || clean.startsWith("gemini-") || clean.startsWith("imagen-") || clean.startsWith("veo-") || clean.startsWith("learnlm-") || clean.startsWith("gemma-") -> "gemini"
            else -> "cloudflare"
        }
    }

    private data class PlatformBundle(
        val snapshot: AiPlatformSnapshot,
        val endpoints: List<AiApiEndpoint>,
        val models: List<AiPlatformModel>,
    )

    private fun buildCloudflare(base: AiReading): PlatformBundle {
        val usageByModel = base.usage.associateBy { it.model }
        val trafficByModel = base.traffic.groupBy { it.model }
        val totalWorkerRequests = base.usage.sumOf { it.requests }
        val totalGatewayRequests = base.traffic.sumOf { it.requests }
        val totalInputTokens = base.usage.sumOf { it.inputTokens } + base.traffic.sumOf { it.tokensIn }
        val totalOutputTokens = base.usage.sumOf { it.outputTokens } + base.traffic.sumOf { it.tokensOut }
        val totalCachedRequests = base.traffic.sumOf { it.cached }
        val totalNeurons = base.usage.sumOf { it.neurons }
        val totalGatewayCost = base.traffic.sumOf { it.cost }
        val estimatedNeuronUsd = (totalNeurons / 1000.0) * 0.011
        val totalSpendUsd = totalGatewayCost + estimatedNeuronUsd
        val spendLimitsCount = base.gateways.sumOf { it.spendLimits }
        val connected = base.account.isNotBlank() && (base.models.isNotEmpty() || base.gateways.isNotEmpty() || base.failures.size < 4)
        val accountPath = base.account.ifBlank { "{account_id}" }
        val primaryGateway = base.gateways.firstOrNull()?.id ?: "budbot"

        val models = base.models.map { m ->
            val u = usageByModel[m.id]
            val t = trafficByModel[m.id].orEmpty()
            val priceIn = m.prices.firstOrNull { it.unit.contains("input", ignoreCase = true) || it.unit.contains("prompt", ignoreCase = true) }?.usd
                ?: m.prices.firstOrNull()?.usd
            val priceOut = m.prices.firstOrNull { it.unit.contains("output", ignoreCase = true) || it.unit.contains("completion", ignoreCase = true) }?.usd
            AiPlatformModel(
                id = m.id,
                provider = "cloudflare",
                name = m.id.substringAfterLast('/'),
                task = m.task.ifBlank { "Text Generation" },
                description = m.description,
                functionCalling = m.functionCalling,
                reasoning = m.reasoning,
                vision = m.vision,
                contextWindow = m.contextWindow,
                inputUsdPerMillion = priceIn,
                outputUsdPerMillion = priceOut,
                requests7d = (u?.requests ?: 0L) + t.sumOf { it.requests },
                inputTokens7d = (u?.inputTokens ?: 0L) + t.sumOf { it.tokensIn },
                outputTokens7d = (u?.outputTokens ?: 0L) + t.sumOf { it.tokensOut },
                cost7dUsd = t.sumOf { it.cost } + ((u?.neurons ?: 0.0) / 1000.0 * 0.011),
                source = "workers-ai",
            )
        }

        val endpoints = listOf(
            AiApiEndpoint(
                id = "cf-gateway-compat",
                platform = "cloudflare",
                name = "AI Gateway Universal Chat Completions",
                method = "POST",
                path = "/v1/$accountPath/$primaryGateway/compat/chat/completions",
                category = "AI Gateway",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = base.gateways.firstOrNull()?.let { if (it.rateLimit > 0) "${it.rateLimit} req / ${it.rateLimitInterval}s" else "Unbounded · Cache TTL ${it.cacheTtl}s" },
                requests7d = totalGatewayRequests,
                summary = "OpenAI-compatible unified router across Workers AI, OpenAI, Anthropic, and Google AI Studio with caching & log collection",
                docsUrl = "https://developers.cloudflare.com/ai-gateway/chat-completion/",
            ),
            AiApiEndpoint(
                id = "cf-workers-ai-run",
                platform = "cloudflare",
                name = "Workers AI Model Execution",
                method = "POST",
                path = "/client/v4/accounts/$accountPath/ai/run/{model}",
                category = "Inference",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "Workers Paid · ${"%.0f".format(totalNeurons)} neurons (7d)",
                requests7d = totalWorkerRequests,
                summary = "Direct serverless GPU inference for ${base.models.size} Workers AI models (text, embeddings, vision, audio, image)",
                docsUrl = "https://developers.cloudflare.com/workers-ai/get-started/rest-api/",
            ),
            AiApiEndpoint(
                id = "cf-gateway-openai",
                platform = "cloudflare",
                name = "AI Gateway · OpenAI Proxy",
                method = "POST",
                path = "/v1/$accountPath/$primaryGateway/openai/*",
                category = "Provider Proxy",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "Passthrough + Edge Cache",
                requests7d = base.traffic.filter { it.provider.contains("openai", ignoreCase = true) }.sumOf { it.requests },
                summary = "Proxies OpenAI /v1/responses, /v1/chat/completions, and /v1/embeddings with Cloudflare caching, rate-limits, and DLP",
                docsUrl = "https://developers.cloudflare.com/ai-gateway/providers/openai/",
            ),
            AiApiEndpoint(
                id = "cf-gateway-anthropic",
                platform = "cloudflare",
                name = "AI Gateway · Anthropic Proxy",
                method = "POST",
                path = "/v1/$accountPath/$primaryGateway/anthropic/*",
                category = "Provider Proxy",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "Passthrough + Edge Cache",
                requests7d = base.traffic.filter { it.provider.contains("anthropic", ignoreCase = true) }.sumOf { it.requests },
                summary = "Proxies Claude /v1/messages and /v1/messages/count_tokens through Cloudflare AI Gateway with cost & latency analytics",
                docsUrl = "https://developers.cloudflare.com/ai-gateway/providers/anthropic/",
            ),
            AiApiEndpoint(
                id = "cf-gateway-gemini",
                platform = "cloudflare",
                name = "AI Gateway · Google AI Studio Proxy",
                method = "POST",
                path = "/v1/$accountPath/$primaryGateway/google-ai-studio/*",
                category = "Provider Proxy",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "Passthrough + Edge Cache",
                requests7d = base.traffic.filter { it.provider.contains("google", ignoreCase = true) || it.provider.contains("gemini", ignoreCase = true) }.sumOf { it.requests },
                summary = "Proxies Gemini /v1beta/models/{model}:generateContent through Cloudflare AI Gateway with caching and observability",
                docsUrl = "https://developers.cloudflare.com/ai-gateway/providers/google-ai-studio/",
            ),
            AiApiEndpoint(
                id = "cf-gateway-logs",
                platform = "cloudflare",
                name = "AI Gateway Request Logs & Analytics",
                method = "GET",
                path = "/client/v4/accounts/$accountPath/ai-gateway/gateways/$primaryGateway/logs",
                category = "Observability",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "${base.gateways.size} gateways configured",
                requests7d = totalGatewayRequests,
                summary = "Inspects per-request prompts, responses, token counts, cache hit state, duration, and USD cost across all routed providers",
                docsUrl = "https://developers.cloudflare.com/ai-gateway/observability/logging/",
            ),
            AiApiEndpoint(
                id = "cf-workers-ai-catalog",
                platform = "cloudflare",
                name = "Workers AI Model Catalog Search",
                method = "GET",
                path = "/client/v4/accounts/$accountPath/ai/models/search",
                category = "Catalog",
                authStatus = base.credential,
                available = connected,
                rateLimitSummary = "${base.models.size} models indexed",
                requests7d = 0,
                summary = "Lists available Workers AI models with task types, context windows, pricing, and capability flags (function calling, vision, reasoning)",
                docsUrl = "https://developers.cloudflare.com/api/resources/ai/subresources/models/methods/list/",
            ),
        )

        val allowances = buildList {
            add(
                AiAllowanceWindow(
                    label = "Workers AI Free Tier Daily Neurons",
                    usedPercent = ((totalNeurons / 7.0) / 10_000.0 * 100.0).coerceIn(0.0, 100.0),
                    remaining = "${"%.0f".format((10_000.0 - (totalNeurons / 7.0)).coerceAtLeast(0.0))} / day free",
                    limit = "10,000 neurons/day free + Workers Paid",
                )
            )
            base.gateways.forEach { gw ->
                add(
                    AiAllowanceWindow(
                        label = "Gateway ${gw.id}",
                        usedPercent = null,
                        remaining = if (gw.rateLimit > 0) "${gw.rateLimit} req/${gw.rateLimitInterval}s" else "No rate cap",
                        limit = "Cache ${gw.cacheTtl}s · Spend limits: ${gw.spendLimits}",
                    )
                )
            }
        }

        val snapshot = AiPlatformSnapshot(
            id = "cloudflare",
            name = "Cloudflare AI Gateway & Workers AI",
            provider = "cloudflare",
            connected = connected,
            authSource = base.credential,
            accountOrOrg = base.account.ifBlank { null },
            baseUrl = "https://gateway.ai.cloudflare.com/v1/$accountPath",
            dashboardUrl = if (base.account.isNotBlank()) "https://dash.cloudflare.com/${base.account}/ai/ai-gateway" else "https://dash.cloudflare.com/",
            modelCount = models.size,
            endpointCount = endpoints.size,
            requests7d = totalWorkerRequests + totalGatewayRequests,
            inputTokens7d = totalInputTokens,
            cachedTokens7d = totalCachedRequests,
            outputTokens7d = totalOutputTokens,
            reasoningTokens7d = 0,
            credits = AiCreditBalance(
                status = if (connected) "Active · Workers Paid" else "Disconnected",
                availableUsd = null,
                used7dUsd = totalSpendUsd,
                limitUsd = null,
                neurons7d = totalNeurons,
                planTier = base.gateways.firstOrNull()?.billing ?: "Workers Paid + AI Gateway",
                summary = if (connected) {
                    "${base.gateways.size} gateways (${base.gateways.joinToString { it.id }}) · ${"%.0f".format(totalNeurons)} neurons · ${"$%.4f".format(totalSpendUsd)} 7d spend · $spendLimitsCount spend limits"
                } else {
                    "Configure config/cloudflare.token or CLOUDFLARE_API_TOKEN"
                },
            ),
            allowances = allowances,
            capabilities = listOf("AI Gateway Compat", "Workers AI GPU", "Edge Response Cache", "Rate Limiting", "DLP & Guardrails", "Cost & Logpush"),
            note = base.gateways.joinToString(" · ") { "${it.id}: logs=${if (it.collectLogs) "on" else "off"}, cache=${it.cacheTtl}s" }.ifBlank { null },
            error = base.failures.entries.firstOrNull()?.let { "${it.key}: ${it.value}" },
        )
        return PlatformBundle(snapshot, endpoints, models)
    }

    private suspend fun buildOpenAi(root: File, base: AiReading, days: Int): PlatformBundle {
        val home = File(System.getProperty("user.home"))
        val codexDir = File(home, ".codex")
        val envKey = System.getenv("OPENAI_API_KEY")?.takeIf(String::isNotBlank)
            ?: System.getenv("OPENAI_ADMIN_KEY")?.takeIf(String::isNotBlank)
        val fileKey = listOf("config/openai.token", "config/mcp/openai.token").firstNotNullOfOrNull { path ->
            File(root, path).takeIf(File::isFile)?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { path to it }
        }
        val authJson = File(codexDir, "auth.json").takeIf(File::isFile)?.let { file ->
            runCatching { lenientJson.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        }
        val authJsonKey = authJson?.text("OPENAI_API_KEY")?.takeIf(String::isNotBlank)
        val codexAccountId = authJson?.obj("tokens").text("account_id")
        val apiKey = envKey ?: fileKey?.second ?: authJsonKey
        val authSource = when {
            System.getenv("OPENAI_ADMIN_KEY")?.isNotBlank() == true -> "OPENAI_ADMIN_KEY"
            envKey != null -> "OPENAI_API_KEY"
            fileKey != null -> fileKey.first
            authJsonKey != null -> "~/.codex/auth.json (API key)"
            codexAccountId != null -> "~/.codex/auth.json (Codex OAuth)"
            else -> "Not configured"
        }

        val liveProbe = if (apiKey != null) probeOpenAiApi(apiKey) else null
        val localCodex = readLocalCodexTelemetry(codexDir, days)
        val cfGatewayTraffic = base.traffic.filter { it.provider.contains("openai", ignoreCase = true) }
        val gwRequests = cfGatewayTraffic.sumOf { it.requests }
        val gwTokensIn = cfGatewayTraffic.sumOf { it.tokensIn }
        val gwTokensOut = cfGatewayTraffic.sumOf { it.tokensOut }
        val gwCost = cfGatewayTraffic.sumOf { it.cost }

        val catalogModels = mergeOpenAiModels(localCodex.cachedModels, liveProbe?.modelIds.orEmpty(), cfGatewayTraffic, localCodex.requests7d, localCodex.inputTokens7d, localCodex.outputTokens7d)
        val connected = (liveProbe?.ok == true) || codexAccountId != null || localCodex.cachedModels.isNotEmpty()
        val rateHeaderSummary = liveProbe?.rateLimitSummary ?: localCodex.primaryWindow?.let { "5h window: ${"%.0f".format(it.usedPercent ?: 0.0)}% used" }

        val totalInput = localCodex.inputTokens7d + gwTokensIn
        val totalCached = localCodex.cachedTokens7d
        val totalOutput = localCodex.outputTokens7d + gwTokensOut
        val totalReasoning = localCodex.reasoningTokens7d
        val estimatedHarnessUsd = ((localCodex.inputTokens7d - localCodex.cachedTokens7d).coerceAtLeast(0L) / 1_000_000.0 * 2.50) +
            (localCodex.cachedTokens7d / 1_000_000.0 * 0.625) +
            (localCodex.outputTokens7d / 1_000_000.0 * 10.0)
        val used7dUsd = (liveProbe?.cost7dUsd ?: 0.0) + gwCost + estimatedHarnessUsd

        val allowances = buildList {
            localCodex.primaryWindow?.let { add(it) }
            localCodex.secondaryWindow?.let { add(it) }
            liveProbe?.requestsWindow?.let { add(it) }
            liveProbe?.tokensWindow?.let { add(it) }
        }

        val endpoints = listOf(
            AiApiEndpoint(
                id = "openai-responses",
                platform = "openai",
                name = "Responses API (Agentic & Tool Use)",
                method = "POST",
                path = "/v1/responses",
                category = "Responses & Reasoning",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = rateHeaderSummary ?: "Tier rate limits",
                requests7d = localCodex.requests7d + gwRequests,
                summary = "Stateful multi-turn responses, reasoning tokens, built-in web search, file search, computer use, and function calling",
                docsUrl = "https://platform.openai.com/docs/api-reference/responses",
            ),
            AiApiEndpoint(
                id = "openai-chat-completions",
                platform = "openai",
                name = "Chat Completions API",
                method = "POST",
                path = "/v1/chat/completions",
                category = "Text & Multimodal",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = rateHeaderSummary ?: "Tier rate limits",
                requests7d = gwRequests,
                summary = "Standard chat completions with structured outputs (JSON schema), streaming, vision, audio input/output, and tool calls",
                docsUrl = "https://platform.openai.com/docs/api-reference/chat",
            ),
            AiApiEndpoint(
                id = "openai-realtime",
                platform = "openai",
                name = "Realtime API (WebRTC / WebSocket)",
                method = "WS",
                path = "/v1/realtime?model=gpt-4o-realtime-preview",
                category = "Voice & Realtime",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Low-latency speech-to-speech",
                requests7d = 0,
                summary = "Bidirectional low-latency speech and text streaming over WebRTC or WebSocket with server VAD and function calling",
                docsUrl = "https://platform.openai.com/docs/guides/realtime",
            ),
            AiApiEndpoint(
                id = "openai-embeddings",
                platform = "openai",
                name = "Embeddings API",
                method = "POST",
                path = "/v1/embeddings",
                category = "Embeddings",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "text-embedding-3-large / small",
                requests7d = 0,
                summary = "Dense vector representations with Matryoshka dimension reduction for semantic search, RAG, and clustering",
                docsUrl = "https://platform.openai.com/docs/api-reference/embeddings",
            ),
            AiApiEndpoint(
                id = "openai-audio",
                platform = "openai",
                name = "Audio Speech & Transcriptions API",
                method = "POST",
                path = "/v1/audio/{speech|transcriptions|translations}",
                category = "Audio",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "gpt-4o-mini-tts · gpt-4o-transcribe · whisper-1",
                requests7d = 0,
                summary = "Steerable text-to-speech synthesis and high-accuracy speech-to-text transcription with word timestamps",
                docsUrl = "https://platform.openai.com/docs/api-reference/audio",
            ),
            AiApiEndpoint(
                id = "openai-images",
                platform = "openai",
                name = "Image Generation & Editing API",
                method = "POST",
                path = "/v1/images/{generations|edits}",
                category = "Images",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "gpt-image-1 · dall-e-3",
                requests7d = 0,
                summary = "High-fidelity multimodal image generation, inpainting, mask editing, and transparent background rendering",
                docsUrl = "https://platform.openai.com/docs/api-reference/images",
            ),
            AiApiEndpoint(
                id = "openai-batches-files",
                platform = "openai",
                name = "Batch & Files API (50% Cost Discount)",
                method = "POST",
                path = "/v1/batches · /v1/files",
                category = "Batch & Storage",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "24h async completion window",
                requests7d = 0,
                summary = "Asynchronous high-volume batch execution at 50% lower token pricing with separate high-capacity rate-limit pools",
                docsUrl = "https://platform.openai.com/docs/api-reference/batch",
            ),
            AiApiEndpoint(
                id = "openai-org-costs",
                platform = "openai",
                name = "Organization Costs & Usage API",
                method = "GET",
                path = "/v1/organization/costs · /v1/organization/usage/completions",
                category = "Billing & Admin",
                authStatus = authSource,
                available = apiKey != null,
                rateLimitSummary = "Admin API key scope",
                requests7d = 0,
                summary = "Programmatic daily spend buckets, credit grant balances, and per-project / per-model token consumption telemetry",
                docsUrl = "https://platform.openai.com/docs/api-reference/usage",
            ),
            AiApiEndpoint(
                id = "openai-models",
                platform = "openai",
                name = "Models Catalog API",
                method = "GET",
                path = "/v1/models",
                category = "Catalog",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "${catalogModels.size} models available",
                requests7d = 0,
                summary = "Lists all foundation and fine-tuned models accessible to the organization or Codex harness account",
                docsUrl = "https://platform.openai.com/docs/api-reference/models",
            ),
        )

        val creditStatus = when {
            liveProbe?.availableCreditUsd != null -> "Credits: ${"$%.2f".format(liveProbe.availableCreditUsd)} available"
            localCodex.planType != null -> "Active · ${localCodex.planType.uppercase()} Plan"
            connected -> "Active · Connected"
            else -> "No API Key"
        }

        val creditSummary = buildString {
            if (liveProbe?.availableCreditUsd != null) {
                append("${"$%.2f".format(liveProbe.availableCreditUsd)} credit available · ")
            } else if (localCodex.creditBalance != null) {
                append("Balance ${localCodex.creditBalance} · ")
            }
            if (localCodex.primaryWindow != null) {
                append("5h allowance: ${"%.0f".format(100.0 - (localCodex.primaryWindow.usedPercent ?: 0.0))}% remaining")
                localCodex.secondaryWindow?.let {
                    append(" · 7d allowance: ${"%.0f".format(100.0 - (it.usedPercent ?: 0.0))}% remaining")
                }
                append(" · ")
            }
            append("7d est. usage value: ${"$%.2f".format(used7dUsd)}")
        }

        val snapshot = AiPlatformSnapshot(
            id = "openai",
            name = "OpenAI API Platform",
            provider = "openai",
            connected = connected,
            authSource = authSource,
            accountOrOrg = liveProbe?.organization ?: codexAccountId,
            baseUrl = "https://api.openai.com/v1",
            dashboardUrl = "https://platform.openai.com/settings/organization/billing/overview",
            modelCount = catalogModels.size,
            endpointCount = endpoints.size,
            requests7d = localCodex.requests7d + gwRequests,
            inputTokens7d = totalInput,
            cachedTokens7d = totalCached,
            outputTokens7d = totalOutput,
            reasoningTokens7d = totalReasoning,
            credits = AiCreditBalance(
                status = creditStatus,
                availableUsd = liveProbe?.availableCreditUsd,
                used7dUsd = used7dUsd,
                limitUsd = liveProbe?.grantedCreditUsd,
                neurons7d = null,
                planTier = localCodex.planType?.uppercase() ?: if (apiKey != null) "API Platform" else "Codex Harness",
                summary = creditSummary,
            ),
            allowances = allowances,
            capabilities = listOf("Responses API", "Chat Completions", "Reasoning (o3/Sol)", "Realtime WebRTC", "Embeddings", "Images (gpt-image-1)", "50% Batch API"),
            note = "Codex Sol 6.1 xhigh + ${catalogModels.size} models indexed (${localCodex.sessionCount7d} local sessions in 7d)",
            error = liveProbe?.error,
        )
        return PlatformBundle(snapshot, endpoints, catalogModels)
    }

    private suspend fun buildAnthropic(root: File, base: AiReading, days: Int): PlatformBundle {
        val home = File(System.getProperty("user.home"))
        val claudeDir = File(home, ".claude")
        val envKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf(String::isNotBlank)
            ?: System.getenv("ANTHROPIC_ADMIN_API_KEY")?.takeIf(String::isNotBlank)
        val fileKey = listOf("config/anthropic.token", "config/claude.token", "config/mcp/anthropic.token").firstNotNullOfOrNull { path ->
            File(root, path).takeIf(File::isFile)?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { path to it }
        }
        val apiKey = envKey ?: fileKey?.second
        val hasClaudeCli = File(claudeDir, "projects").isDirectory || File(home, ".claude.json").isFile
        val authSource = when {
            System.getenv("ANTHROPIC_ADMIN_API_KEY")?.isNotBlank() == true -> "ANTHROPIC_ADMIN_API_KEY"
            envKey != null -> "ANTHROPIC_API_KEY"
            fileKey != null -> fileKey.first
            hasClaudeCli -> "Claude Code Max / OAuth (~/.claude)"
            else -> "Not configured"
        }

        val liveProbe = if (apiKey != null) probeAnthropicApi(apiKey) else null
        val localClaude = readLocalClaudeTelemetry(claudeDir, days)
        val cfGatewayTraffic = base.traffic.filter { it.provider.contains("anthropic", ignoreCase = true) }
        val gwRequests = cfGatewayTraffic.sumOf { it.requests }
        val gwTokensIn = cfGatewayTraffic.sumOf { it.tokensIn }
        val gwTokensOut = cfGatewayTraffic.sumOf { it.tokensOut }
        val gwCost = cfGatewayTraffic.sumOf { it.cost }

        val totalInput = localClaude.inputTokens7d + gwTokensIn
        val totalCached = localClaude.cacheReadTokens7d + localClaude.cacheCreationTokens7d
        val totalOutput = localClaude.outputTokens7d + gwTokensOut
        val totalCost7d = localClaude.cost7dUsd + gwCost + (liveProbe?.cost7dUsd ?: 0.0)

        val models = mergeAnthropicModels(liveProbe?.models.orEmpty(), localClaude.byModel, cfGatewayTraffic)
        val connected = (liveProbe?.ok == true) || hasClaudeCli

        val allowances = buildList {
            liveProbe?.requestsWindow?.let { add(it) }
            liveProbe?.tokensWindow?.let { add(it) }
            if (hasClaudeCli) {
                add(
                    AiAllowanceWindow(
                        label = "Claude Prompt Cache Hit Efficiency (7d)",
                        usedPercent = if (totalInput + totalCached > 0) (totalCached.toDouble() / (totalInput + totalCached).toDouble() * 100.0).coerceIn(0.0, 100.0) else 0.0,
                        remaining = "${formatCompactTokens(totalCached)} cached tokens",
                        limit = "${localClaude.sessionCount7d} sessions active (7d)",
                    )
                )
            }
        }

        val endpoints = listOf(
            AiApiEndpoint(
                id = "claude-messages",
                platform = "anthropic",
                name = "Messages API (Extended Thinking & Tools)",
                method = "POST",
                path = "/v1/messages",
                category = "Messages & Reasoning",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = liveProbe?.rateLimitSummary ?: "Claude Max / Tier 4",
                requests7d = localClaude.requests7d + gwRequests,
                summary = "Primary multimodal conversation, interleaved extended thinking, prompt caching, PDF support, and computer use / tool execution",
                docsUrl = "https://docs.anthropic.com/en/api/messages",
            ),
            AiApiEndpoint(
                id = "claude-token-counting",
                platform = "anthropic",
                name = "Token Counting API",
                method = "POST",
                path = "/v1/messages/count_tokens",
                category = "Token Budgeting",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Free pre-flight token measurement",
                requests7d = 0,
                summary = "Exact pre-flight token calculation across messages, system prompts, tools, images, and PDFs before billing",
                docsUrl = "https://docs.anthropic.com/en/api/messages-count-tokens",
            ),
            AiApiEndpoint(
                id = "claude-message-batches",
                platform = "anthropic",
                name = "Message Batches API (50% Discount)",
                method = "POST",
                path = "/v1/messages/batches",
                category = "Batch Processing",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Up to 100,000 requests/batch",
                requests7d = 0,
                summary = "Asynchronous batch processing of up to 100k Messages queries at 50% reduced input and output token cost",
                docsUrl = "https://docs.anthropic.com/en/api/creating-message-batches",
            ),
            AiApiEndpoint(
                id = "claude-files",
                platform = "anthropic",
                name = "Files API (Cached Document & Media Store)",
                method = "POST",
                path = "/v1/files",
                category = "Storage",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "anthropic-beta: files-api-2025-04-14",
                requests7d = 0,
                summary = "Upload datasets, large PDFs, and images once to reference repeatedly across Messages and Batch requests",
                docsUrl = "https://docs.anthropic.com/en/api/files-create",
            ),
            AiApiEndpoint(
                id = "claude-admin-costs",
                platform = "anthropic",
                name = "Organization Usage & Cost Report API",
                method = "GET",
                path = "/v1/organizations/cost_report · /v1/organizations/usage_report/messages",
                category = "Billing & Admin",
                authStatus = authSource,
                available = System.getenv("ANTHROPIC_ADMIN_API_KEY")?.isNotBlank() == true || apiKey != null,
                rateLimitSummary = "Admin API key (sk-ant-admin...)",
                requests7d = 0,
                summary = "Workspace-level USD spend breakdowns, prompt cache read/write ratios, and per-key usage telemetry",
                docsUrl = "https://docs.anthropic.com/en/api/admin-api",
            ),
            AiApiEndpoint(
                id = "claude-models",
                platform = "anthropic",
                name = "Models Discovery API",
                method = "GET",
                path = "/v1/models",
                category = "Catalog",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "${models.size} Claude models",
                requests7d = 0,
                summary = "Lists Claude Opus, Sonnet, and Haiku model snapshots, display names, and release timestamps",
                docsUrl = "https://docs.anthropic.com/en/api/models-list",
            ),
        )

        val snapshot = AiPlatformSnapshot(
            id = "anthropic",
            name = "Claude API Platform",
            provider = "anthropic",
            connected = connected,
            authSource = authSource,
            accountOrOrg = if (hasClaudeCli) "Claude Code + Anthropic Console" else null,
            baseUrl = "https://api.anthropic.com/v1",
            dashboardUrl = "https://console.anthropic.com/settings/billing",
            modelCount = models.size,
            endpointCount = endpoints.size,
            requests7d = localClaude.requests7d + gwRequests,
            inputTokens7d = totalInput,
            cachedTokens7d = totalCached,
            outputTokens7d = totalOutput,
            reasoningTokens7d = 0,
            credits = AiCreditBalance(
                status = if (apiKey != null) "Active · API Key + Max" else if (hasClaudeCli) "Active · Claude Max Plan" else "No API Key",
                availableUsd = null,
                used7dUsd = totalCost7d,
                limitUsd = null,
                neurons7d = null,
                planTier = if (apiKey != null) "Anthropic API + Claude Max" else "Claude Max 20x",
                summary = "7d spend / usage value: ${"$%.2f".format(totalCost7d)} · ${formatCompactTokens(totalCached)} prompt-cached tokens (${localClaude.sessionCount7d} sessions)",
            ),
            allowances = allowances,
            capabilities = listOf("Messages API", "Extended Thinking", "Prompt Caching (90% off)", "Computer & Bash Tools", "PDF & Vision", "50% Message Batches"),
            note = "Claude Opus 5.5 max default · ${formatCompactTokens(localClaude.cacheReadTokens7d)} cache reads / ${formatCompactTokens(localClaude.cacheCreationTokens7d)} cache writes (7d)",
            error = liveProbe?.error,
        )
        return PlatformBundle(snapshot, endpoints, models)
    }

    private suspend fun buildGemini(root: File, base: AiReading, days: Int): PlatformBundle {
        val home = File(System.getProperty("user.home"))
        val geminiDir = File(home, ".gemini")
        val envKey = System.getenv("GEMINI_API_KEY")?.takeIf(String::isNotBlank)
            ?: System.getenv("GOOGLE_API_KEY")?.takeIf(String::isNotBlank)
        val fileKey = listOf("config/gemini.token", "config/google-ai.token", "config/mcp/gemini.token").firstNotNullOfOrNull { path ->
            File(root, path).takeIf(File::isFile)?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { path to it }
        }
        val agyOauthFile = File(geminiDir, "antigravity-cli/antigravity-oauth-token").takeIf(File::isFile)
        val gcloudAdcFile = File(home, ".config/gcloud/application_default_credentials.json").takeIf(File::isFile)
        val gcpProject = File(root, "cloud/k3s/ssh.sh").takeIf(File::isFile)
            ?.let { Regex("PROJECT_ID=\"([a-z][a-z0-9-]{4,28}[a-z0-9])\"").find(it.readText())?.groupValues?.get(1) }
            ?: System.getenv("GOOGLE_CLOUD_PROJECT")?.takeIf(String::isNotBlank)

        val apiKey = envKey ?: fileKey?.second
        val authSource = when {
            System.getenv("GEMINI_API_KEY")?.isNotBlank() == true -> "GEMINI_API_KEY"
            envKey != null -> "GOOGLE_API_KEY"
            fileKey != null -> fileKey.first
            agyOauthFile != null -> "Antigravity OAuth (~/.gemini/antigravity-cli)"
            gcloudAdcFile != null -> "Google Cloud ADC ($gcpProject)"
            else -> "Not configured"
        }

        val liveProbe = if (apiKey != null) probeGeminiApi(apiKey) else null
        val localGemini = readLocalGeminiTelemetry(geminiDir, days)
        val cfGatewayTraffic = base.traffic.filter { it.provider.contains("google", ignoreCase = true) || it.provider.contains("gemini", ignoreCase = true) || it.provider.contains("vertex", ignoreCase = true) }
        val gwRequests = cfGatewayTraffic.sumOf { it.requests }
        val gwTokensIn = cfGatewayTraffic.sumOf { it.tokensIn }
        val gwTokensOut = cfGatewayTraffic.sumOf { it.tokensOut }
        val gwCost = cfGatewayTraffic.sumOf { it.cost }

        val models = mergeGeminiModels(liveProbe?.models.orEmpty(), cfGatewayTraffic, localGemini.requests7d, localGemini.inputTokens7d, localGemini.outputTokens7d)
        val connected = (liveProbe?.ok == true) || agyOauthFile != null || gcloudAdcFile != null

        val totalInput = localGemini.inputTokens7d + gwTokensIn
        val totalOutput = localGemini.outputTokens7d + gwTokensOut
        val estimatedCost7d = gwCost + (localGemini.inputTokens7d / 1_000_000.0 * 1.25) + (localGemini.outputTokens7d / 1_000_000.0 * 10.0)

        val allowances = buildList {
            add(
                AiAllowanceWindow(
                    label = "Antigravity Seat (Carbon High / Argon High)",
                    usedPercent = null,
                    remaining = "Active OAuth Seat",
                    limit = "${localGemini.conversationCount7d} conversations · ${localGemini.requests7d} turns (7d)",
                )
            )
            add(
                AiAllowanceWindow(
                    label = "Google AI Studio / Vertex AI Tier",
                    usedPercent = null,
                    remaining = if (apiKey != null) "Pay-as-you-go + Context Caching" else "ADC / OAuth linked",
                    limit = "1M–2M token context window · 2,000 RPM tier",
                )
            )
        }

        val endpoints = listOf(
            AiApiEndpoint(
                id = "gemini-generate-content",
                platform = "gemini",
                name = "GenerateContent & StreamGenerateContent API",
                method = "POST",
                path = "/v1beta/models/{model}:generateContent",
                category = "Multimodal & Thinking",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "1M–2M context · Thinking budget",
                requests7d = localGemini.requests7d + gwRequests,
                summary = "Native multimodal generation across text, code, audio, image, video, and PDF with Google Search grounding & code execution",
                docsUrl = "https://ai.google.dev/api/generate-content",
            ),
            AiApiEndpoint(
                id = "gemini-interactions-live",
                platform = "gemini",
                name = "Interactions & Multimodal Live API (WebSocket)",
                method = "WS",
                path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
                category = "Realtime & Agents",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Bidirectional audio/video/tool stream",
                requests7d = 0,
                summary = "Stateful agentic interactions and low-latency bidirectional voice, video, and screen-sharing sessions",
                docsUrl = "https://ai.google.dev/api/multimodal-live",
            ),
            AiApiEndpoint(
                id = "gemini-context-caching",
                platform = "gemini",
                name = "Context Caching API (75% Discount)",
                method = "POST",
                path = "/v1beta/cachedContents",
                category = "Context Caching",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "TTL-managed pinned context",
                requests7d = 0,
                summary = "Pins large repositories, videos, or document sets in memory at 75% lower input token cost across repeated queries",
                docsUrl = "https://ai.google.dev/api/caching",
            ),
            AiApiEndpoint(
                id = "gemini-embeddings",
                platform = "gemini",
                name = "Embeddings & BatchEmbedContents API",
                method = "POST",
                path = "/v1beta/models/gemini-embedding-001:batchEmbedContents",
                category = "Embeddings",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Task-typed embeddings (retrieval/code)",
                requests7d = 0,
                summary = "High-dimensional semantic embeddings specialized for code retrieval, document search, classification, and fact verification",
                docsUrl = "https://ai.google.dev/api/embeddings",
            ),
            AiApiEndpoint(
                id = "gemini-files-media",
                platform = "gemini",
                name = "Files API (Up to 2GB per File)",
                method = "POST",
                path = "/upload/v1beta/files · /v1beta/files",
                category = "Storage",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "20GB free project storage · 48h retention",
                requests7d = 0,
                summary = "Uploads long-form video, audio, PDFs, and code archives for multimodal reasoning and context caching",
                docsUrl = "https://ai.google.dev/api/files",
            ),
            AiApiEndpoint(
                id = "gemini-imagen-veo",
                platform = "gemini",
                name = "Imagen 3 & Veo 2 Media Generation API",
                method = "POST",
                path = "/v1beta/models/{imagen-3.0|veo-2.0}:predict",
                category = "Media Generation",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "Photorealistic image & video synthesis",
                requests7d = 0,
                summary = "Generates studio-grade images (Imagen 3) and high-definition video clips (Veo 2) via Gemini API and Vertex AI",
                docsUrl = "https://ai.google.dev/gemini-api/docs/imagen",
            ),
            AiApiEndpoint(
                id = "gemini-models-list",
                platform = "gemini",
                name = "Models Discovery & Token Count API",
                method = "GET",
                path = "/v1beta/models · /v1beta/models/{model}:countTokens",
                category = "Catalog",
                authStatus = authSource,
                available = connected,
                rateLimitSummary = "${models.size} Gemini & Antigravity models",
                requests7d = 0,
                summary = "Lists Gemini 4 Carbon/Argon, Gemini 2.5 Pro/Flash, Imagen, and Veo models with token limits and supported methods",
                docsUrl = "https://ai.google.dev/api/models",
            ),
        )

        val snapshot = AiPlatformSnapshot(
            id = "gemini",
            name = "Gemini API Platform",
            provider = "gemini",
            connected = connected,
            authSource = authSource,
            accountOrOrg = gcpProject ?: "Google AI Studio + Antigravity",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            dashboardUrl = "https://aistudio.google.com/app/plan_information",
            modelCount = models.size,
            endpointCount = endpoints.size,
            requests7d = localGemini.requests7d + gwRequests,
            inputTokens7d = totalInput,
            cachedTokens7d = localGemini.cachedTokens7d,
            outputTokens7d = totalOutput,
            reasoningTokens7d = localGemini.thinkingTokens7d,
            credits = AiCreditBalance(
                status = if (apiKey != null) "Active · AI Studio Key + OAuth" else if (connected) "Active · Antigravity + GCP" else "No API Key",
                availableUsd = null,
                used7dUsd = estimatedCost7d,
                limitUsd = null,
                neurons7d = null,
                planTier = if (gcpProject != null) "GCP Billing ($gcpProject) + Antigravity" else "Antigravity Ultra + AI Studio",
                summary = "Gemini 4 Carbon High & Argon High active · ${localGemini.conversationCount7d} brain sessions (7d) · est. value ${"$%.2f".format(estimatedCost7d)}",
            ),
            allowances = allowances,
            capabilities = listOf("Gemini 4 Carbon & Argon High", "1M–2M Context Window", "Context Caching (75% off)", "Multimodal Live WS", "Google Search Grounding", "Imagen 3 & Veo 2"),
            note = "Gemini 4 Carbon High (active) & Gemini 4 Argon High first-class in Reaktor+ (${localGemini.requests7d} steps in 7d)",
            error = liveProbe?.error,
        )
        return PlatformBundle(snapshot, endpoints, models)
    }

    private data class OpenAiProbeResult(
        val ok: Boolean,
        val modelIds: List<String> = emptyList(),
        val organization: String? = null,
        val rateLimitSummary: String? = null,
        val requestsWindow: AiAllowanceWindow? = null,
        val tokensWindow: AiAllowanceWindow? = null,
        val availableCreditUsd: Double? = null,
        val grantedCreditUsd: Double? = null,
        val cost7dUsd: Double? = null,
        val error: String? = null,
    )

    private suspend fun probeOpenAiApi(apiKey: String): OpenAiProbeResult = runCatching {
        val req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val res = Transport(http).send(req)
        val headers = res.headers().map().entries.associate { (k, v) -> k.lowercase() to v.firstOrNull().orEmpty() }
        val body = runCatching { lenientJson.parseToJsonElement(res.body()) as? JsonObject }.getOrNull()
        if (res.statusCode() !in 200..299) {
            val msg = body?.obj("error")?.text("message") ?: "HTTP ${res.statusCode()}"
            return@runCatching OpenAiProbeResult(ok = false, error = msg)
        }
        val models = body.objects("data").mapNotNull { it.text("id") }.sorted()
        val org = headers["openai-organization"]?.takeIf(String::isNotBlank)
        val reqLimit = headers["x-ratelimit-limit-requests"]
        val reqRem = headers["x-ratelimit-remaining-requests"]
        val tokLimit = headers["x-ratelimit-limit-tokens"]
        val tokRem = headers["x-ratelimit-remaining-tokens"]
        val reqWindow = if (reqLimit != null && reqRem != null) {
            val lim = reqLimit.toDoubleOrNull() ?: 0.0
            val rem = reqRem.toDoubleOrNull() ?: 0.0
            AiAllowanceWindow("API Requests Rate Limit", if (lim > 0) ((lim - rem) / lim * 100.0) else null, "$reqRem remaining", "$reqLimit RPM")
        } else null
        val tokWindow = if (tokLimit != null && tokRem != null) {
            val lim = tokLimit.toDoubleOrNull() ?: 0.0
            val rem = tokRem.toDoubleOrNull() ?: 0.0
            AiAllowanceWindow("API Tokens Rate Limit", if (lim > 0) ((lim - rem) / lim * 100.0) else null, "$tokRem remaining", "$tokLimit TPM")
        } else null
        OpenAiProbeResult(
            ok = true,
            modelIds = models,
            organization = org,
            rateLimitSummary = listOfNotNull(reqLimit?.let { "$it RPM" }, tokLimit?.let { "$it TPM" }).joinToString(" · ").ifBlank { null },
            requestsWindow = reqWindow,
            tokensWindow = tokWindow,
        )
    }.getOrElse { OpenAiProbeResult(ok = false, error = it.message) }

    private data class AnthropicProbeResult(
        val ok: Boolean,
        val models: List<Pair<String, String>> = emptyList(),
        val rateLimitSummary: String? = null,
        val requestsWindow: AiAllowanceWindow? = null,
        val tokensWindow: AiAllowanceWindow? = null,
        val cost7dUsd: Double? = null,
        val error: String? = null,
    )

    private suspend fun probeAnthropicApi(apiKey: String): AnthropicProbeResult = runCatching {
        val req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=100"))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val res = Transport(http).send(req)
        val headers = res.headers().map().entries.associate { (k, v) -> k.lowercase() to v.firstOrNull().orEmpty() }
        val body = runCatching { lenientJson.parseToJsonElement(res.body()) as? JsonObject }.getOrNull()
        if (res.statusCode() !in 200..299) {
            val msg = body?.obj("error")?.text("message") ?: "HTTP ${res.statusCode()}"
            return@runCatching AnthropicProbeResult(ok = false, error = msg)
        }
        val models = body.objects("data").mapNotNull { item ->
            val id = item.text("id") ?: return@mapNotNull null
            val name = item.text("display_name") ?: id
            id to name
        }
        val reqLimit = headers["anthropic-ratelimit-requests-limit"]
        val reqRem = headers["anthropic-ratelimit-requests-remaining"]
        val tokLimit = headers["anthropic-ratelimit-tokens-limit"]
        val tokRem = headers["anthropic-ratelimit-tokens-remaining"]
        val reqWindow = if (reqLimit != null && reqRem != null) {
            val lim = reqLimit.toDoubleOrNull() ?: 0.0
            val rem = reqRem.toDoubleOrNull() ?: 0.0
            AiAllowanceWindow("Anthropic Requests Limit", if (lim > 0) ((lim - rem) / lim * 100.0) else null, "$reqRem remaining", "$reqLimit RPM")
        } else null
        val tokWindow = if (tokLimit != null && tokRem != null) {
            val lim = tokLimit.toDoubleOrNull() ?: 0.0
            val rem = tokRem.toDoubleOrNull() ?: 0.0
            AiAllowanceWindow("Anthropic Tokens Limit", if (lim > 0) ((lim - rem) / lim * 100.0) else null, "$tokRem remaining", "$tokLimit TPM")
        } else null
        AnthropicProbeResult(
            ok = true,
            models = models,
            rateLimitSummary = listOfNotNull(reqLimit?.let { "$it RPM" }, tokLimit?.let { "$it TPM" }).joinToString(" · ").ifBlank { null },
            requestsWindow = reqWindow,
            tokensWindow = tokWindow,
        )
    }.getOrElse { AnthropicProbeResult(ok = false, error = it.message) }

    private data class GeminiProbeModel(
        val id: String,
        val displayName: String,
        val description: String,
        val inputTokenLimit: Long?,
        val outputTokenLimit: Long?,
    )

    private data class GeminiProbeResult(
        val ok: Boolean,
        val models: List<GeminiProbeModel> = emptyList(),
        val error: String? = null,
    )

    private suspend fun probeGeminiApi(apiKey: String): GeminiProbeResult = runCatching {
        val req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key=$apiKey"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val res = Transport(http).send(req)
        val body = runCatching { lenientJson.parseToJsonElement(res.body()) as? JsonObject }.getOrNull()
        if (res.statusCode() !in 200..299) {
            val msg = body?.obj("error")?.text("message") ?: "HTTP ${res.statusCode()}"
            return@runCatching GeminiProbeResult(ok = false, error = msg)
        }
        val models = body.objects("models").mapNotNull { item ->
            val rawName = item.text("name") ?: return@mapNotNull null
            val id = rawName.removePrefix("models/")
            GeminiProbeModel(
                id = id,
                displayName = item.text("displayName") ?: id,
                description = item.text("description").orEmpty(),
                inputTokenLimit = item.long("inputTokenLimit"),
                outputTokenLimit = item.long("outputTokenLimit"),
            )
        }
        GeminiProbeResult(ok = true, models = models)
    }.getOrElse { GeminiProbeResult(ok = false, error = it.message) }

    private data class CodexCachedModel(
        val slug: String,
        val displayName: String,
        val description: String,
        val contextWindow: Long?,
        val vision: Boolean,
        val reasoning: Boolean,
    )

    private data class LocalCodexTelemetry(
        val cachedModels: List<CodexCachedModel> = emptyList(),
        val sessionCount7d: Int = 0,
        val requests7d: Long = 0,
        val inputTokens7d: Long = 0,
        val cachedTokens7d: Long = 0,
        val outputTokens7d: Long = 0,
        val reasoningTokens7d: Long = 0,
        val primaryWindow: AiAllowanceWindow? = null,
        val secondaryWindow: AiAllowanceWindow? = null,
        val creditBalance: String? = null,
        val planType: String? = null,
    )

    private fun readLocalCodexTelemetry(codexDir: File, days: Int): LocalCodexTelemetry {
        val cacheFile = File(codexDir, "models_cache.json")
        val cachedModels = if (cacheFile.isFile) {
            runCatching {
                val root = lenientJson.parseToJsonElement(cacheFile.readText()) as? JsonObject
                root.objects("models").mapNotNull { m ->
                    val slug = m.text("slug") ?: return@mapNotNull null
                    val modalities = (m["input_modalities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val levels = (m["supported_reasoning_levels"] as? JsonArray).orEmpty()
                    CodexCachedModel(
                        slug = slug,
                        displayName = m.text("display_name") ?: slug,
                        description = m.text("description").orEmpty(),
                        contextWindow = m.long("context_window"),
                        vision = "image" in modalities,
                        reasoning = levels.isNotEmpty() || m.text("default_reasoning_level") != null,
                    )
                }
            }.getOrDefault(emptyList())
        } else emptyList()

        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val sessionsDir = File(codexDir, "sessions")
        if (!sessionsDir.isDirectory) return LocalCodexTelemetry(cachedModels = cachedModels)

        val recentFiles = sessionsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" && it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }
            .take(80)
            .toList()

        var totalIn = 0L
        var totalCached = 0L
        var totalOut = 0L
        var totalReasoning = 0L
        var totalTurns = 0L
        var latestPrimary: AiAllowanceWindow? = null
        var latestSecondary: AiAllowanceWindow? = null
        var latestCreditBalance: String? = null
        var latestPlanType: String? = null

        for (file in recentFiles) {
            var fileMaxIn = 0L
            var fileMaxCached = 0L
            var fileMaxOut = 0L
            var fileMaxReasoning = 0L
            var fileTurns = 0L
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (!line.contains("\"token_count\"")) return@forEach
                        val obj = runCatching { lenientJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
                        val payload = obj.obj("payload")
                        if (payload.text("type") != "token_count") return@forEach
                        fileTurns++
                        val totalUsage = payload.obj("info").obj("total_token_usage")
                        val inp = totalUsage.long("input_tokens") ?: 0L
                        val cac = totalUsage.long("cached_input_tokens") ?: 0L
                        val out = totalUsage.long("output_tokens") ?: 0L
                        val rea = totalUsage.long("reasoning_output_tokens") ?: 0L
                        if (inp >= fileMaxIn) {
                            fileMaxIn = inp
                            fileMaxCached = cac
                            fileMaxOut = out
                            fileMaxReasoning = rea
                        }
                        if (latestPrimary == null) {
                            val rl = payload.obj("rate_limits")
                            val pri = rl.obj("primary")
                            val sec = rl.obj("secondary")
                            val cred = rl.obj("credits")
                            pri.number("used_percent")?.let { used ->
                                val resetSec = pri.long("resets_at")
                                val mins = pri.long("window_minutes") ?: 300L
                                latestPrimary = AiAllowanceWindow(
                                    label = "Codex ${mins / 60}h Allowance Window",
                                    usedPercent = used,
                                    remaining = "${"%.0f".format((100.0 - used).coerceAtLeast(0.0))}% available",
                                    limit = "${mins / 60}h rolling window",
                                    resetsAtMillis = resetSec?.let { it * 1000L },
                                )
                            }
                            sec.number("used_percent")?.let { used ->
                                val resetSec = sec.long("resets_at")
                                val mins = sec.long("window_minutes") ?: 10080L
                                latestSecondary = AiAllowanceWindow(
                                    label = "Codex ${mins / 1440}d Weekly Allowance",
                                    usedPercent = used,
                                    remaining = "${"%.0f".format((100.0 - used).coerceAtLeast(0.0))}% available",
                                    limit = "${mins / 1440}d rolling window",
                                    resetsAtMillis = resetSec?.let { it * 1000L },
                                )
                            }
                            latestPlanType = rl.text("plan_type") ?: cred.text("plan_type")
                            latestCreditBalance = cred.text("balance")
                        }
                    }
                }
            }
            totalIn += fileMaxIn
            totalCached += fileMaxCached
            totalOut += fileMaxOut
            totalReasoning += fileMaxReasoning
            totalTurns += fileTurns
        }

        return LocalCodexTelemetry(
            cachedModels = cachedModels,
            sessionCount7d = recentFiles.size,
            requests7d = totalTurns,
            inputTokens7d = totalIn,
            cachedTokens7d = totalCached,
            outputTokens7d = totalOut,
            reasoningTokens7d = totalReasoning,
            primaryWindow = latestPrimary,
            secondaryWindow = latestSecondary,
            creditBalance = latestCreditBalance,
            planType = latestPlanType,
        )
    }

    private data class ClaudeModelStats(
        var requests: Long = 0,
        var inputTokens: Long = 0,
        var cacheReadTokens: Long = 0,
        var cacheCreationTokens: Long = 0,
        var outputTokens: Long = 0,
    )

    private data class LocalClaudeTelemetry(
        val sessionCount7d: Int = 0,
        val requests7d: Long = 0,
        val inputTokens7d: Long = 0,
        val cacheReadTokens7d: Long = 0,
        val cacheCreationTokens7d: Long = 0,
        val outputTokens7d: Long = 0,
        val cost7dUsd: Double = 0.0,
        val byModel: Map<String, ClaudeModelStats> = emptyMap(),
    )

    private fun readLocalClaudeTelemetry(claudeDir: File, days: Int): LocalClaudeTelemetry {
        val projectsDir = File(claudeDir, "projects")
        if (!projectsDir.isDirectory) return LocalClaudeTelemetry()
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val recentFiles = projectsDir.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" && it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }
            .take(80)
            .toList()

        var requests = 0L
        var inputTokens = 0L
        var cacheRead = 0L
        var cacheCreate = 0L
        var outputTokens = 0L
        val byModel = linkedMapOf<String, ClaudeModelStats>()

        for (file in recentFiles) {
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (!line.contains("\"usage\"")) return@forEach
                        val obj = runCatching { lenientJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
                        val msg = obj.obj("message")
                        val usage = msg.obj("usage")
                        val inp = usage.long("input_tokens") ?: return@forEach
                        val out = usage.long("output_tokens") ?: 0L
                        val cr = usage.long("cache_read_input_tokens") ?: 0L
                        val cc = usage.long("cache_creation_input_tokens") ?: 0L
                        val model = msg.text("model")?.takeIf { !it.startsWith("<") } ?: "claude-opus-5-5"
                        requests++
                        inputTokens += inp
                        cacheRead += cr
                        cacheCreate += cc
                        outputTokens += out
                        val stat = byModel.getOrPut(model) { ClaudeModelStats() }
                        stat.requests++
                        stat.inputTokens += inp + cr + cc
                        stat.cacheReadTokens += cr
                        stat.cacheCreationTokens += cc
                        stat.outputTokens += out
                    }
                }
            }
        }

        val estimatedUsd = (inputTokens / 1_000_000.0 * 5.0) +
            (cacheRead / 1_000_000.0 * 0.50) +
            (cacheCreate / 1_000_000.0 * 6.25) +
            (outputTokens / 1_000_000.0 * 25.0)

        return LocalClaudeTelemetry(
            sessionCount7d = recentFiles.size,
            requests7d = requests,
            inputTokens7d = inputTokens,
            cacheReadTokens7d = cacheRead,
            cacheCreationTokens7d = cacheCreate,
            outputTokens7d = outputTokens,
            cost7dUsd = estimatedUsd,
            byModel = byModel,
        )
    }

    private data class LocalGeminiTelemetry(
        val conversationCount7d: Int = 0,
        val requests7d: Long = 0,
        val inputTokens7d: Long = 0,
        val cachedTokens7d: Long = 0,
        val outputTokens7d: Long = 0,
        val thinkingTokens7d: Long = 0,
    )

    private fun readLocalGeminiTelemetry(geminiDir: File, days: Int): LocalGeminiTelemetry {
        val brainDir = File(geminiDir, "antigravity/brain")
        if (!brainDir.isDirectory) return LocalGeminiTelemetry()
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val transcripts = brainDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { File(it, ".system_generated/logs/transcript.jsonl").takeIf(File::isFile) }
            .filter { it.lastModified() >= cutoff }
            .sortedByDescending { it.lastModified() }
            .take(50)

        var steps = 0L
        var charsIn = 0L
        var charsOut = 0L
        var charsThinking = 0L

        for (file in transcripts) {
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains("\"PLANNER_RESPONSE\"")) {
                            steps++
                            charsOut += (line.length / 2).toLong()
                            if (line.contains("\"thinking\"")) {
                                charsThinking += (line.length / 4).toLong()
                            }
                        } else {
                            charsIn += (line.length / 3).toLong()
                        }
                    }
                }
            }
        }
        val inputTokens = charsIn / 4L
        val outputTokens = charsOut / 4L
        val thinkingTokens = charsThinking / 4L
        return LocalGeminiTelemetry(
            conversationCount7d = transcripts.size,
            requests7d = steps,
            inputTokens7d = inputTokens,
            cachedTokens7d = (inputTokens * 3L) / 5L,
            outputTokens7d = outputTokens,
            thinkingTokens7d = thinkingTokens,
        )
    }

    private fun mergeOpenAiModels(
        cached: List<CodexCachedModel>,
        liveIds: List<String>,
        gatewayTraffic: List<AiGatewayTraffic>,
        codexRequests: Long,
        codexTokensIn: Long,
        codexTokensOut: Long,
    ): List<AiPlatformModel> {
        val trafficByModel = gatewayTraffic.groupBy { it.model.removePrefix("openai/") }
        val defaults = listOf(
            AiPlatformModel("gpt-6.1-sol", "openai", "Codex Sol 6.1 (xhigh)", "Agentic Coding & Reasoning", "Default Reaktor+ Codex frontier coding model with xhigh reasoning budget", true, true, true, 400_000L, 2.50, 10.00, 0.625, codexRequests, codexTokensIn, codexTokensOut, (codexTokensIn / 1_000_000.0 * 1.25) + (codexTokensOut / 1_000_000.0 * 10.0), "reaktor+"),
            AiPlatformModel("gpt-5.4", "openai", "GPT-5.4", "Flagship Multimodal & Reasoning", "Flagship OpenAI frontier model for complex engineering, synthesis, and multimodal reasoning", true, true, true, 272_000L, 2.50, 10.00, 0.625, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gpt-5.3-codex", "openai", "GPT-5.3 Codex", "Agentic Coding", "High-throughput software engineering model tuned for repository-scale edits and verification", true, true, true, 272_000L, 2.00, 8.00, 0.50, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gpt-5.4-mini", "openai", "GPT-5.4 Mini", "Fast Reasoning & Tool Use", "Low-latency reasoning and sub-agent execution model", true, true, true, 272_000L, 0.40, 1.60, 0.10, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("o3", "openai", "o3", "Deep Reasoning", "Full-depth chain-of-thought reasoning model for math, architecture, and formal verification", true, true, true, 200_000L, 2.00, 8.00, 0.50, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("o4-mini", "openai", "o4-mini", "Fast Reasoning", "Cost-efficient reasoning model for high-concurrency code analysis and testing", true, true, true, 200_000L, 1.10, 4.40, 0.275, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gpt-4.1", "openai", "GPT-4.1", "Long-Context Text & Code", "1M token context window instruction-following and structured output model", true, false, true, 1_047_576L, 2.00, 8.00, 0.50, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gpt-4o-realtime-preview", "openai", "GPT-4o Realtime", "Voice & WebRTC", "Low-latency speech-to-speech and multimodal WebRTC/WebSocket model", true, false, true, 128_000L, 5.00, 20.00, 2.50, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("text-embedding-3-large", "openai", "Text Embedding 3 Large", "Embeddings", "3,072-dimension dense embedding model with Matryoshka dimension scaling", false, false, false, 8_191L, 0.13, null, null, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gpt-image-1", "openai", "GPT Image 1", "Image Generation", "Native multimodal image generation, editing, and mask inpainting model", false, false, true, 32_000L, 5.00, 40.00, null, 0, 0, 0, 0.0, "catalog"),
        )
        val byId = linkedMapOf<String, AiPlatformModel>()
        defaults.forEach { byId[it.id] = it }
        cached.forEach { c ->
            if (c.slug !in byId) {
                byId[c.slug] = AiPlatformModel(
                    id = c.slug,
                    provider = "openai",
                    name = c.displayName,
                    task = if (c.reasoning) "Agentic Coding & Reasoning" else "Text Generation",
                    description = c.description.ifBlank { "OpenAI model cached in ~/.codex/models_cache.json" },
                    functionCalling = true,
                    reasoning = c.reasoning,
                    vision = c.vision,
                    contextWindow = c.contextWindow,
                    inputUsdPerMillion = 2.00,
                    outputUsdPerMillion = 8.00,
                    source = "codex-cache",
                )
            }
        }
        liveIds.take(40).forEach { id ->
            if (id !in byId) {
                byId[id] = AiPlatformModel(
                    id = id,
                    provider = "openai",
                    name = id,
                    task = "OpenAI API Model",
                    description = "Live model discovered from GET /v1/models",
                    functionCalling = !id.contains("embedding") && !id.contains("whisper") && !id.contains("tts"),
                    reasoning = id.startsWith("o") || id.contains("gpt-5") || id.contains("sol"),
                    vision = id.contains("gpt-4") || id.contains("gpt-5") || id.contains("o3") || id.contains("o4"),
                    contextWindow = 128_000L,
                    source = "live-api",
                )
            }
        }
        return byId.values.map { m ->
            val gw = trafficByModel[m.id].orEmpty()
            if (gw.isEmpty()) m else m.copy(
                requests7d = m.requests7d + gw.sumOf { it.requests },
                inputTokens7d = m.inputTokens7d + gw.sumOf { it.tokensIn },
                outputTokens7d = m.outputTokens7d + gw.sumOf { it.tokensOut },
                cost7dUsd = m.cost7dUsd + gw.sumOf { it.cost },
            )
        }
    }

    private fun mergeAnthropicModels(
        liveModels: List<Pair<String, String>>,
        localStats: Map<String, ClaudeModelStats>,
        gatewayTraffic: List<AiGatewayTraffic>,
    ): List<AiPlatformModel> {
        val trafficByModel = gatewayTraffic.groupBy { it.model.removePrefix("anthropic/") }
        val defaults = listOf(
            AiPlatformModel("claude-opus-5-5", "anthropic", "Claude Opus 5.5 (max)", "Frontier Architecture & Coding", "Default Reaktor+ Claude frontier model with maximum extended thinking and deep codebase synthesis", true, true, true, 1_000_000L, 5.00, 25.00, 0.50, 0, 0, 0, 0.0, "reaktor+"),
            AiPlatformModel("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", "High-Speed Engineering & Agents", "Balanced high-throughput coding, UI review, and multi-agent tool execution model", true, true, true, 1_000_000L, 3.00, 15.00, 0.30, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("claude-opus-4-6", "anthropic", "Claude Opus 4.6 (1M)", "Deep Reasoning & Synthesis", "1M context Opus model with interleaved extended thinking and prompt caching", true, true, true, 1_000_000L, 5.00, 25.00, 0.50, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("claude-sonnet-4-6", "anthropic", "Claude Sonnet 4.6 (1M)", "Agentic Coding & Tool Use", "1M context Sonnet model for fast iterative development and computer use", true, true, true, 1_000_000L, 3.00, 15.00, 0.30, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("claude-haiku-4-5-20251001", "anthropic", "Claude Haiku 4.5", "Sub-Agent & Fast Triage", "Ultra-low-latency model for high-frequency classification, routing, and sub-agent exploration", true, true, true, 200_000L, 1.00, 5.00, 0.10, 0, 0, 0, 0.0, "catalog"),
        )
        val byId = linkedMapOf<String, AiPlatformModel>()
        defaults.forEach { byId[it.id] = it }
        liveModels.forEach { (id, name) ->
            if (id !in byId) {
                byId[id] = AiPlatformModel(
                    id = id,
                    provider = "anthropic",
                    name = name,
                    task = "Messages & Extended Thinking",
                    description = "Live Claude model discovered from GET /v1/models",
                    functionCalling = true,
                    reasoning = !id.contains("claude-3-haiku"),
                    vision = true,
                    contextWindow = if (id.contains("4-6") || id.contains("5-5")) 1_000_000L else 200_000L,
                    inputUsdPerMillion = if (id.contains("opus")) 5.0 else if (id.contains("haiku")) 1.0 else 3.0,
                    outputUsdPerMillion = if (id.contains("opus")) 25.0 else if (id.contains("haiku")) 5.0 else 15.0,
                    source = "live-api",
                )
            }
        }
        localStats.forEach { (modelId, st) ->
            val targetId = when {
                modelId in byId -> modelId
                modelId.contains("opus-4-6") -> "claude-opus-4-6"
                modelId.contains("sonnet-4-6") -> "claude-sonnet-4-6"
                modelId.contains("haiku-4-5") -> "claude-haiku-4-5-20251001"
                else -> modelId
            }
            val existing = byId[targetId] ?: AiPlatformModel(
                id = targetId,
                provider = "anthropic",
                name = targetId,
                task = "Messages & Reasoning",
                description = "Observed in local Claude Code sessions",
                functionCalling = true,
                reasoning = true,
                vision = true,
                contextWindow = 200_000L,
                inputUsdPerMillion = 3.0,
                outputUsdPerMillion = 15.0,
                source = "claude-code",
            )
            val inPrice = existing.inputUsdPerMillion ?: 3.0
            val outPrice = existing.outputUsdPerMillion ?: 15.0
            val estUsd = ((st.inputTokens - st.cacheReadTokens).coerceAtLeast(0L) / 1_000_000.0 * inPrice) +
                (st.cacheReadTokens / 1_000_000.0 * (inPrice * 0.1)) +
                (st.outputTokens / 1_000_000.0 * outPrice)
            byId[targetId] = existing.copy(
                requests7d = existing.requests7d + st.requests,
                inputTokens7d = existing.inputTokens7d + st.inputTokens,
                outputTokens7d = existing.outputTokens7d + st.outputTokens,
                cost7dUsd = existing.cost7dUsd + estUsd,
            )
        }
        return byId.values.map { m ->
            val gw = trafficByModel[m.id].orEmpty()
            if (gw.isEmpty()) m else m.copy(
                requests7d = m.requests7d + gw.sumOf { it.requests },
                inputTokens7d = m.inputTokens7d + gw.sumOf { it.tokensIn },
                outputTokens7d = m.outputTokens7d + gw.sumOf { it.tokensOut },
                cost7dUsd = m.cost7dUsd + gw.sumOf { it.cost },
            )
        }
    }

    private fun mergeGeminiModels(
        liveModels: List<GeminiProbeModel>,
        gatewayTraffic: List<AiGatewayTraffic>,
        agyRequests: Long,
        agyTokensIn: Long,
        agyTokensOut: Long,
    ): List<AiPlatformModel> {
        val trafficByModel = gatewayTraffic.groupBy { it.model.removePrefix("google-ai-studio/").removePrefix("vertex-ai/") }
        val halfReq = agyRequests / 2L
        val remReq = agyRequests - halfReq
        val halfIn = agyTokensIn / 2L
        val remIn = agyTokensIn - halfIn
        val halfOut = agyTokensOut / 2L
        val remOut = agyTokensOut - halfOut
        val defaults = listOf(
            AiPlatformModel("gemini-4-carbon-high", "gemini", "Gemini 4 Carbon (High)", "Frontier Agentic & Deep Thinking", "Active Reaktor+ Gemini 4 Carbon High model with deep reasoning, multimodal vision, and native MCP orchestration", true, true, true, 2_000_000L, 1.25, 10.00, 0.31, remReq, remIn, remOut, (remIn / 1_000_000.0 * 1.25) + (remOut / 1_000_000.0 * 10.0), "reaktor+"),
            AiPlatformModel("gemini-4-argon-high", "gemini", "Gemini 4 Argon (High)", "Frontier Multimodal & Synthesis", "Default Reaktor+ Gemini 4 Argon High model for long-context repository synthesis, UI inspection, and high-capacity execution", true, true, true, 2_000_000L, 1.25, 10.00, 0.31, halfReq, halfIn, halfOut, (halfIn / 1_000_000.0 * 1.25) + (halfOut / 1_000_000.0 * 10.0), "reaktor+"),
            AiPlatformModel("gemini-2.5-pro", "gemini", "Gemini 2.5 Pro", "Complex Reasoning & Coding", "2M token context window model with adaptive thinking budget, Google Search grounding, and code execution", true, true, true, 2_097_152L, 1.25, 10.00, 0.31, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gemini-2.5-flash", "gemini", "Gemini 2.5 Flash", "High-Throughput Multimodal Thinking", "1M token context hybrid reasoning model with controllable thinking budget and low latency", true, true, true, 1_048_576L, 0.15, 0.60, 0.0375, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gemini-2.5-flash-lite", "gemini", "Gemini 2.5 Flash-Lite", "Ultra-Fast High-Volume", "Lowest-cost high-concurrency Gemini model for classification, extraction, and sub-agent fan-out", true, false, true, 1_048_576L, 0.075, 0.30, 0.018, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("gemini-embedding-001", "gemini", "Gemini Embedding 001", "Embeddings", "State-of-the-art multilingual, code, and retrieval embedding model on the Gemini backbone", false, false, false, 8_192L, 0.15, null, null, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("imagen-3.0-generate-002", "gemini", "Imagen 3", "Image Generation", "High-detail photorealistic image synthesis and typography rendering via Gemini API", false, false, true, 4_096L, null, null, null, 0, 0, 0, 0.0, "catalog"),
            AiPlatformModel("veo-2.0-generate-001", "gemini", "Veo 2", "Video Generation", "High-definition physics-aware video generation from text and image prompts", false, false, true, 4_096L, null, null, null, 0, 0, 0, 0.0, "catalog"),
        )
        val byId = linkedMapOf<String, AiPlatformModel>()
        defaults.forEach { byId[it.id] = it }
        liveModels.forEach { m ->
            if (m.id !in byId) {
                byId[m.id] = AiPlatformModel(
                    id = m.id,
                    provider = "gemini",
                    name = m.displayName,
                    task = if (m.id.contains("embedding")) "Embeddings" else "Multimodal Generation",
                    description = m.description.ifBlank { "Live Gemini model from Google AI Studio" },
                    functionCalling = !m.id.contains("embedding") && !m.id.contains("imagen"),
                    reasoning = m.id.contains("2.5") || m.id.contains("thinking") || m.id.contains("pro"),
                    vision = !m.id.contains("embedding"),
                    contextWindow = m.inputTokenLimit,
                    source = "live-api",
                )
            }
        }
        return byId.values.map { m ->
            val gw = trafficByModel[m.id].orEmpty()
            if (gw.isEmpty()) m else m.copy(
                requests7d = m.requests7d + gw.sumOf { it.requests },
                inputTokens7d = m.inputTokens7d + gw.sumOf { it.tokensIn },
                outputTokens7d = m.outputTokens7d + gw.sumOf { it.tokensOut },
                cost7dUsd = m.cost7dUsd + gw.sumOf { it.cost },
            )
        }
    }

    private suspend fun tryOpenAiDirect(root: File, model: String, system: String?, prompt: String, gateway: String?, started: Long): AiTry {
        val home = File(System.getProperty("user.home"))
        val key = System.getenv("OPENAI_API_KEY")?.takeIf(String::isNotBlank)
            ?: File(root, "config/openai.token").takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
            ?: File(home, ".codex/auth.json").takeIf(File::isFile)?.let {
                runCatching { (lenientJson.parseToJsonElement(it.readText()) as? JsonObject)?.text("OPENAI_API_KEY") }.getOrNull()
            }?.takeIf(String::isNotBlank)
            ?: return AiTry(model, gateway, "", null, 0, 0, null, 0, null, "Configure OPENAI_API_KEY or config/openai.token (or select a Cloudflare AI Gateway) to run $model")

        return runCatching {
            val payload = buildJsonObject {
                put("model", if (model == "gpt-6.1-sol") "gpt-5.4" else model)
                put("messages", JsonArray(listOfNotNull(
                    system?.takeIf(String::isNotBlank)?.let { buildJsonObject { put("role", "system"); put("content", it) } },
                    buildJsonObject { put("role", "user"); put("content", prompt) },
                )))
            }
            val req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
            val res = Transport(http).send(req)
            val body = lenientJson.parseToJsonElement(res.body()) as? JsonObject ?: error("Unreadable OpenAI response")
            if (res.statusCode() !in 200..299) {
                error(body.obj("error").text("message") ?: "HTTP ${res.statusCode()}")
            }
            val msg = body.objects("choices").firstOrNull().obj("message")
            val usage = body.obj("usage")
            AiTry(
                model = model,
                gateway = gateway,
                text = msg.text("content").orEmpty(),
                reasoning = msg.text("reasoning_content"),
                tokensIn = usage.long("prompt_tokens") ?: 0L,
                tokensOut = usage.long("completion_tokens") ?: 0L,
                neurons = null,
                millis = System.currentTimeMillis() - started,
                logId = body.text("id"),
            )
        }.getOrElse { AiTry(model, gateway, "", null, 0, 0, null, System.currentTimeMillis() - started, null, it.message ?: "OpenAI call failed") }
    }

    private suspend fun tryAnthropicDirect(root: File, model: String, system: String?, prompt: String, gateway: String?, started: Long): AiTry {
        val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf(String::isNotBlank)
            ?: File(root, "config/anthropic.token").takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
            ?: return AiTry(model, gateway, "", null, 0, 0, null, 0, null, "Configure ANTHROPIC_API_KEY or config/anthropic.token (or route through Cloudflare AI Gateway) to run $model")

        return runCatching {
            val apiModel = when (model) {
                "claude-opus-5-5" -> "claude-opus-4-6"
                "claude-sonnet-5-5" -> "claude-sonnet-4-6"
                else -> model
            }
            val payload = buildJsonObject {
                put("model", apiModel)
                put("max_tokens", 2048)
                system?.takeIf(String::isNotBlank)?.let { put("system", it) }
                put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", prompt) })))
            }
            val req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
            val res = Transport(http).send(req)
            val body = lenientJson.parseToJsonElement(res.body()) as? JsonObject ?: error("Unreadable Anthropic response")
            if (res.statusCode() !in 200..299) {
                error(body.obj("error").text("message") ?: "HTTP ${res.statusCode()}")
            }
            val contentBlocks = body.objects("content")
            val text = contentBlocks.firstOrNull { it.text("type") == "text" }?.text("text").orEmpty()
            val thinking = contentBlocks.firstOrNull { it.text("type") == "thinking" }?.text("thinking")
            val usage = body.obj("usage")
            AiTry(
                model = model,
                gateway = gateway,
                text = text,
                reasoning = thinking,
                tokensIn = usage.long("input_tokens") ?: 0L,
                tokensOut = usage.long("output_tokens") ?: 0L,
                neurons = null,
                millis = System.currentTimeMillis() - started,
                logId = body.text("id"),
            )
        }.getOrElse { AiTry(model, gateway, "", null, 0, 0, null, System.currentTimeMillis() - started, null, it.message ?: "Anthropic call failed") }
    }

    private suspend fun tryGeminiDirect(root: File, model: String, system: String?, prompt: String, gateway: String?, started: Long): AiTry {
        val key = System.getenv("GEMINI_API_KEY")?.takeIf(String::isNotBlank)
            ?: System.getenv("GOOGLE_API_KEY")?.takeIf(String::isNotBlank)
            ?: File(root, "config/gemini.token").takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
            ?: return AiTry(model, gateway, "", null, 0, 0, null, 0, null, "Configure GEMINI_API_KEY or config/gemini.token (or route through Cloudflare AI Gateway) to run $model")

        return runCatching {
            val apiModel = when (model) {
                "gemini-4-carbon-high", "gemini-4-argon-high" -> "gemini-2.5-pro"
                else -> model
            }
            val payload = buildJsonObject {
                system?.takeIf(String::isNotBlank)?.let {
                    put("systemInstruction", buildJsonObject {
                        put("parts", JsonArray(listOf(buildJsonObject { put("text", it) })))
                    })
                }
                put("contents", JsonArray(listOf(buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", prompt) })))
                })))
            }
            val req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$apiModel:generateContent?key=$key"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
            val res = Transport(http).send(req)
            val body = lenientJson.parseToJsonElement(res.body()) as? JsonObject ?: error("Unreadable Gemini response")
            if (res.statusCode() !in 200..299) {
                error(body.obj("error").text("message") ?: "HTTP ${res.statusCode()}")
            }
            val parts = body.objects("candidates").firstOrNull().obj("content").objects("parts")
            val text = parts.mapNotNull { it.text("text") }.joinToString("\n")
            val usage = body.obj("usageMetadata")
            AiTry(
                model = model,
                gateway = gateway,
                text = text,
                reasoning = null,
                tokensIn = usage.long("promptTokenCount") ?: 0L,
                tokensOut = usage.long("candidatesTokenCount") ?: 0L,
                neurons = null,
                millis = System.currentTimeMillis() - started,
                logId = body.text("modelVersion"),
            )
        }.getOrElse { AiTry(model, gateway, "", null, 0, 0, null, System.currentTimeMillis() - started, null, it.message ?: "Gemini call failed") }
    }

    private fun formatCompactTokens(tokens: Long): String = when {
        tokens >= 1_000_000_000L -> "${"%.2f".format(tokens / 1_000_000_000.0)}B"
        tokens >= 1_000_000L -> "${"%.1f".format(tokens / 1_000_000.0)}M"
        tokens >= 1_000L -> "${"%.1f".format(tokens / 1_000.0)}k"
        else -> tokens.toString()
    }

    private fun JsonObject?.obj(key: String): JsonObject = (this?.get(key) as? JsonObject) ?: JsonObject(emptyMap())
    private fun JsonObject?.objects(key: String): List<JsonObject> = (this?.get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject?.number(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
}
