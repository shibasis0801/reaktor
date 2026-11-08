package dev.shibasis.reaktor.cloud.cloudflare

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import dev.shibasis.reaktor.core.framework.lenientJson
import dev.shibasis.reaktor.cloud.http.*

@Serializable
data class AiPrice(val unit: String, val usd: Double)

@Serializable
data class AiModel(
    val id: String,
    val task: String,
    val description: String,
    val functionCalling: Boolean,
    val reasoning: Boolean,
    val vision: Boolean,
    val contextWindow: Long?,
    val prices: List<AiPrice>,
    val beta: Boolean,
    val paidOnly: Boolean,
)

@Serializable
data class AiModelUsage(
    val model: String,
    val requests: Long,
    val failures: Map<Int, Long>,
    val inputTokens: Long,
    val outputTokens: Long,
    val neurons: Double,
    val inferenceMillis: Long,
) {
    val failed: Long get() = failures.values.sum()
}

@Serializable
data class AiGateway(
    val id: String,
    val createdAt: Long?,
    val collectLogs: Boolean,
    val cacheTtl: Long,
    val rateLimit: Long,
    val rateLimitInterval: Long,
    val authentication: Boolean,
    val dlp: Boolean,
    val guardrails: Boolean,
    val spendLimits: Int,
    val billing: String?,
    val logpush: Boolean,
)

@Serializable
data class AiGatewayTraffic(
    val gateway: String,
    val model: String,
    val provider: String,
    val requests: Long,
    val errors: Long,
    val cached: Long,
    val tokensIn: Long,
    val tokensOut: Long,
    val cost: Double,
)

@Serializable
data class AiGatewayLog(
    val id: String,
    val at: Long?,
    val model: String,
    val provider: String,
    val success: Boolean,
    val cached: Boolean,
    val tokensIn: Long,
    val tokensOut: Long,
    val cost: Double?,
    val durationMillis: Long,
    val status: Int?,
    val metadata: String?,
)

@Serializable
data class AiUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = inputTokens + outputTokens,
    val neurons: Double? = null,
    val cost: Double? = null,
)

@Serializable
data class AiMessage(
    val role: String,
    val content: String,
)

@Serializable
data class AiRunTitle(
    val title: String,
    val summary: String,
    val logId: String? = null,
    val cached: Boolean = false,
    val usage: AiUsage = AiUsage(),
)

@Serializable
data class AiChangeDigest(
    val title: String,
    val summary: String,
    val highlights: List<String> = emptyList(),
    val logId: String? = null,
    val cached: Boolean = false,
    val usage: AiUsage = AiUsage(),
)

@Serializable
data class AiPlanItemCandidate(
    val id: String,
    val title: String,
    val kind: String = "Task",
    val status: String = "Todo",
    val priority: String = "Normal",
    val parent: String? = null,
)

@Serializable
data class AiItemTriage(
    val priority: String,
    val parent: String?,
    val after: List<String>,
    val summary: String,
    val logId: String? = null,
    val cached: Boolean = false,
    val usage: AiUsage = AiUsage(),
)

enum class AiGatewayRoute { Compat, WorkersAi }

data class AiCompletion(
    val body: JsonObject,
    val logId: String?,
    val millis: Long,
    val text: String = body.objects("choices").firstOrNull().obj("message").text("content").orEmpty(),
    val reasoning: String? = body.objects("choices").firstOrNull().obj("message").text("reasoning_content"),
    val usage: AiUsage = body.obj("usage").toAiUsage(),
    val cached: Boolean = false,
    val model: String = body.text("model").orEmpty(),
    val gateway: String? = null,
)

private fun JsonObject.toAiUsage(): AiUsage {
    val input = long("prompt_tokens") ?: long("input_tokens") ?: 0L
    val output = long("completion_tokens") ?: long("output_tokens") ?: 0L
    return AiUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = long("total_tokens") ?: (input + output),
        neurons = number("neurons"),
        cost = number("cost"),
    )
}

@Serializable
data class AiTry(
    val model: String,
    val gateway: String?,
    val text: String,
    val reasoning: String?,
    val tokensIn: Long,
    val tokensOut: Long,
    val neurons: Double?,
    val millis: Long,
    val logId: String?,
    val failure: String? = null,
)

@Serializable
data class AiReading(
    val account: String,
    val credential: String,
    val readAtMillis: Long,
    val days: Int,
    val models: List<AiModel>,
    val usage: List<AiModelUsage>,
    val traffic: List<AiGatewayTraffic>,
    val gateways: List<AiGateway>,
    val failures: Map<String, String>,
)

class CloudflareLogin(source: String, private val credentials: CloudflareCredentials, private val recovery: CloudflareCredentials? = null) : CloudflareCredentials by credentials {
    var source: String = source
        private set
    private val lock = Mutex()
    private var recovered: String? = null

    suspend fun <T> authenticated(request: suspend (String) -> T, unauthorized: (T) -> Boolean): T {
        val first = request(recovered ?: credentials.token())
        if (!unauthorized(first) || recovery == null) return first
        val token = lock.withLock {
            recovered ?: recovery.token().also { recovered = it; source = "Wrangler sign-in" }
        }
        return request(token)
    }

    companion object {
        const val TokenFile: String = "config/cloudflare.token"

        fun forWorkspace(root: File): CloudflareLogin {
            val wrangler = WranglerLogin()
            System.getenv("CLOUDFLARE_API_TOKEN")?.takeIf { it.isNotBlank() }
                ?.let { return CloudflareLogin("CLOUDFLARE_API_TOKEN", CloudflareApiToken(it)) }
            listOf(TokenFile, "config/mcp/cloudflare.token").firstNotNullOfOrNull { path ->
                File(root, path).takeIf(File::isFile)?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?.let { CloudflareLogin(path, CloudflareApiToken(it), wrangler) }
            }?.let { return it }
            return CloudflareLogin("Wrangler sign-in", wrangler)
        }
    }
}

object CloudflareAccounts {
    fun of(root: File): String? {
        System.getenv("CLOUDFLARE_ACCOUNT_ID")?.takeIf { it.isNotBlank() }?.let { return it }
        val fleet = dev.shibasis.reaktor.cloud.wrangler.WorkerFleets.read(root) ?: return null
        return fleet.workers.flatMap { worker ->
            worker.configurations.keys.mapNotNull { environment ->
                val (_, config) = dev.shibasis.reaktor.cloud.wrangler.WorkerFleets.configuration(root, worker, environment)
                config["account_id"]?.jsonPrimitive?.contentOrNull
            }
        }.distinct().singleOrNull()
    }
}
class CloudflareAi(
    private val accountId: String,
    private val credentials: CloudflareCredentials,
    private val base: String = DefaultApiBase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gatewayBase: String = if (base == DefaultApiBase) AiGatewayClient.DefaultBase else base,
) {
    private val http = Transport.Shared

    fun gateway(
        id: String = AiGatewayClient.DefaultGateway,
        gatewayCredentials: CloudflareCredentials = credentials,
    ): AiGatewayClient = AiGatewayClient(
        accountId = accountId,
        gateway = id,
        credentials = credentials,
        gatewayCredentials = gatewayCredentials,
        base = gatewayBase,
        clock = clock,
        http = http,
    )

    suspend fun completeGateway(
        request: JsonObject,
        gateway: String = AiGatewayClient.DefaultGateway,
        route: AiGatewayRoute = AiGatewayRoute.Compat,
        cacheKey: String? = null,
        cacheTtlSeconds: Long? = null,
        skipCache: Boolean = false,
        metadata: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 180,
    ): AiCompletion = gateway(gateway).complete(
        request = request,
        route = route,
        cacheKey = cacheKey,
        cacheTtlSeconds = cacheTtlSeconds,
        skipCache = skipCache,
        metadata = metadata,
        timeoutSeconds = timeoutSeconds,
    )

    suspend fun read(source: String, days: Int = 7): AiReading = coroutineScope {
        val models = async { runCatching { models() } }
        val usage = async { runCatching { usage(days) } }
        val traffic = async { runCatching { traffic(days) } }
        val gateways = async { runCatching { gateways() } }
        val parts = mapOf("models" to models.await(), "usage" to usage.await(), "traffic" to traffic.await(), "gateways" to gateways.await())
        AiReading(
            account = accountId,
            credential = source,
            readAtMillis = clock(),
            days = days,
            models = models.await().getOrDefault(emptyList()),
            usage = usage.await().getOrDefault(emptyList()),
            traffic = traffic.await().getOrDefault(emptyList()),
            gateways = gateways.await().getOrDefault(emptyList()),
            failures = parts.mapNotNull { (part, result) -> result.exceptionOrNull()?.let { part to (it.message ?: it::class.simpleName.orEmpty()) } }.toMap(),
        )
    }

    suspend fun models(): List<AiModel> = call("GET", "/accounts/$accountId/ai/models/search?per_page=100").body.objects("result")
        .map { item ->
            val properties = item.objects("properties").associate { it.text("property_id").orEmpty() to it["value"] }
            fun flag(key: String) = (properties[key] as? JsonPrimitive)?.contentOrNull == "true"
            AiModel(
                id = item.text("name").orEmpty(),
                task = item.obj("task").text("name").orEmpty(),
                description = item.text("description").orEmpty(),
                functionCalling = flag("function_calling"),
                reasoning = flag("reasoning"),
                vision = flag("vision"),
                contextWindow = (properties["context_window"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                prices = (properties["price"] as? JsonArray).orEmpty().mapNotNull { price ->
                    (price as? JsonObject)?.let { AiPrice(it.text("unit").orEmpty(), it.number("price") ?: return@mapNotNull null) }
                },
                beta = flag("beta"),
                paidOnly = flag("require_workers_paid"),
            )
        }
        .filter { it.id.isNotEmpty() }
        .sortedWith(compareBy({ it.task }, { it.id }))

    suspend fun usage(days: Int = 7): List<AiModelUsage> {
        val rows = graphql(
            """
            query(${'$'}account: String!, ${'$'}since: Time!, ${'$'}until: Time!) {
              viewer { accounts(filter: { accountTag: ${'$'}account }) {
                aiInferenceAdaptiveGroups(limit: 10000, filter: { datetime_geq: ${'$'}since, datetime_leq: ${'$'}until }) {
                  count
                  sum { totalNeurons totalInputTokens totalOutputTokens totalInferenceTimeMs }
                  dimensions { modelId errorCode }
                }
              } }
            }
            """.trimIndent(), days,
        ).objects("aiInferenceAdaptiveGroups")
        return rows.groupBy { it.obj("dimensions").text("modelId").orEmpty() }.map { (model, groups) ->
            AiModelUsage(
                model = model,
                requests = groups.sumOf { it.long("count") ?: 0 },
                failures = groups.mapNotNull { row ->
                    val code = row.obj("dimensions").long("errorCode")?.toInt() ?: 0
                    if (code == 0) null else code to (row.long("count") ?: 0)
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() },
                inputTokens = groups.sumOf { it.obj("sum").long("totalInputTokens") ?: 0 },
                outputTokens = groups.sumOf { it.obj("sum").long("totalOutputTokens") ?: 0 },
                neurons = groups.sumOf { it.obj("sum").number("totalNeurons") ?: 0.0 },
                inferenceMillis = groups.sumOf { it.obj("sum").long("totalInferenceTimeMs") ?: 0 },
            )
        }.filter { it.model.isNotEmpty() }.sortedByDescending { it.requests }
    }

    suspend fun traffic(days: Int = 7): List<AiGatewayTraffic> = graphql(
        """
        query(${'$'}account: String!, ${'$'}since: Time!, ${'$'}until: Time!) {
          viewer { accounts(filter: { accountTag: ${'$'}account }) {
            aiGatewayRequestsAdaptiveGroups(limit: 10000, filter: { datetimeHour_geq: ${'$'}since, datetimeHour_leq: ${'$'}until }) {
              count
              sum { cost tokensIn tokensOut cachedRequests erroredRequests }
              dimensions { gateway model provider }
            }
          } }
        }
        """.trimIndent(), days,
    ).objects("aiGatewayRequestsAdaptiveGroups").map { row ->
        val dimensions = row.obj("dimensions")
        val sum = row.obj("sum")
        AiGatewayTraffic(
            gateway = dimensions.text("gateway").orEmpty(),
            model = dimensions.text("model").orEmpty(),
            provider = dimensions.text("provider").orEmpty(),
            requests = row.long("count") ?: 0,
            errors = sum.long("erroredRequests") ?: 0,
            cached = sum.long("cachedRequests") ?: 0,
            tokensIn = sum.long("tokensIn") ?: 0,
            tokensOut = sum.long("tokensOut") ?: 0,
            cost = sum.number("cost") ?: 0.0,
        )
    }.sortedByDescending { it.requests }

    suspend fun gateways(): List<AiGateway> =
        call("GET", "/accounts/$accountId/ai-gateway/gateways?per_page=100").body.objects("result").map(::parseGateway).sortedBy { it.id }

    suspend fun logs(gateway: String, limit: Int = 50): List<AiGatewayLog> =
        call("GET", "/accounts/$accountId/ai-gateway/gateways/$gateway/logs?per_page=${limit.coerceIn(1, 50)}&order_by=created_at&order_by_direction=desc")
            .body.objects("result").map { log ->
                AiGatewayLog(
                    id = log.text("id").orEmpty(),
                    at = instantMillis(log.text("created_at")),
                    model = log.text("model").orEmpty(),
                    provider = log.text("provider").orEmpty(),
                    success = log.flag("success") ?: false,
                    cached = log.flag("cached") ?: false,
                    tokensIn = log.long("tokens_in") ?: 0,
                    tokensOut = log.long("tokens_out") ?: 0,
                    cost = log.number("cost"),
                    durationMillis = log.long("duration") ?: 0,
                    status = log.long("status_code")?.toInt(),
                    metadata = log.text("metadata"),
                )
            }

    suspend fun createGateway(id: String): AiGateway = parseGateway(
        call("POST", "/accounts/$accountId/ai-gateway/gateways", buildJsonObject {
            put("id", id)
            put("cache_invalidate_on_update", false)
            put("cache_ttl", 0)
            put("collect_logs", true)
            put("rate_limiting_interval", 0)
            put("rate_limiting_limit", 0)
        }).body.obj("result"),
    )

    suspend fun complete(request: JsonObject, gateway: String? = null, metadata: Map<String, String> = emptyMap()): AiCompletion {
        val headers = buildMap {
            gateway?.let { put("cf-aig-gateway-id", it) }
            if (metadata.isNotEmpty()) put("cf-aig-metadata", metadataJson(metadata))
        }
        val started = clock()
        val response = call("POST", "/accounts/$accountId/ai/v1/chat/completions", request, headers, timeoutSeconds = 180)
        return AiCompletion(
            body = response.body,
            logId = response.header("cf-aig-log-id"),
            millis = clock() - started,
            cached = response.header("cf-aig-cache-status")?.equals("HIT", ignoreCase = true) == true,
            model = request.text("model").orEmpty().ifBlank { response.body.text("model").orEmpty() },
            gateway = gateway,
        )
    }

    private fun parseGateway(item: JsonObject) = AiGateway(
        id = item.text("id").orEmpty(),
        createdAt = instantMillis(item.text("created_at")),
        collectLogs = item.flag("collect_logs") ?: false,
        cacheTtl = item.long("cache_ttl") ?: 0,
        rateLimit = item.long("rate_limiting_limit") ?: 0,
        rateLimitInterval = item.long("rate_limiting_interval") ?: 0,
        authentication = item.flag("authentication") ?: false,
        dlp = item.obj("dlp").flag("enabled") ?: false,
        guardrails = item["guardrails"] is JsonObject,
        spendLimits = item.obj("spend_limits").objects("rules").count { it.flag("enabled") != false },
        billing = item.text("workers_ai_billing_mode"),
        logpush = item.flag("logpush") ?: false,
    )

    private suspend fun graphql(query: String, days: Int): JsonObject {
        val until = Instant.ofEpochMilli(clock())
        val since = until.minus(Duration.ofDays(days.toLong()))
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("account", accountId)
                put("since", since.toString())
                put("until", until.toString())
            })
        }
        val response = call("POST", "/graphql", body).body
        response.objects("errors").firstOrNull()?.let { throw CloudflareFailure("analytics: ${it.text("message")}") }
        return response.obj("data").obj("viewer").objects("accounts").firstOrNull() ?: JsonObject(emptyMap())
    }

    private suspend fun call(method: String, path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = 30): CloudflareResponse =
        requestCloudflareJson(
            http = http,
            credentials = credentials,
            method = method,
            url = base + path,
            label = path.substringBefore('?').substringAfter("/accounts/$accountId"),
            body = body,
            headers = { headers },
            timeoutSeconds = timeoutSeconds,
        )

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        const val DefaultApiBase: String = "https://api.cloudflare.com/client/v4"
    }
}

class AiGatewayClient(
    val accountId: String,
    val gateway: String = DefaultGateway,
    private val credentials: CloudflareCredentials,
    private val gatewayCredentials: CloudflareCredentials = credentials,
    private val base: String = DefaultBase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: HttpClient = Transport.Shared,
) {
    suspend fun complete(
        request: JsonObject,
        route: AiGatewayRoute = AiGatewayRoute.Compat,
        cacheKey: String? = null,
        cacheTtlSeconds: Long? = null,
        skipCache: Boolean = false,
        metadata: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 180,
    ): AiCompletion {
        val rawModel = request.text("model").orEmpty()
        val normalizedModel = when (route) {
            AiGatewayRoute.Compat -> compatModel(rawModel)
            AiGatewayRoute.WorkersAi -> workersAiModel(rawModel)
        }
        val payload = if (normalizedModel.isNotEmpty() && normalizedModel != rawModel) {
            JsonObject(request + ("model" to JsonPrimitive(normalizedModel)))
        } else {
            request
        }
        val endpoint = when (route) {
            AiGatewayRoute.Compat -> "/$gateway/compat/chat/completions"
            AiGatewayRoute.WorkersAi -> "/$gateway/workers-ai/v1/chat/completions"
        }
        val path = "/$accountId$endpoint"
        val explicitGatewayToken = if (gatewayCredentials === credentials) null else gatewayCredentials.token()
        val started = clock()
        val response = requestCloudflareJson(
            http = http,
            credentials = credentials,
            method = "POST",
            url = base.trimEnd('/') + path,
            label = endpoint,
            body = payload,
            headers = { token ->
                buildMap {
                    val gatewayToken = (explicitGatewayToken ?: token).trim()
                    if (gatewayToken.isNotEmpty()) put("cf-aig-authorization", "Bearer $gatewayToken")
                    cacheKey?.trim()?.takeIf(String::isNotEmpty)?.let { put("cf-aig-cache-key", it) }
                    cacheTtlSeconds?.takeIf { it >= 0 }?.let { put("cf-aig-cache-ttl", it.toString()) }
                    if (skipCache) put("cf-aig-skip-cache", "true")
                    if (metadata.isNotEmpty()) put("cf-aig-metadata", metadataJson(metadata))
                }
            },
            timeoutSeconds = timeoutSeconds,
        )
        return AiCompletion(
            body = response.body,
            logId = response.header("cf-aig-log-id"),
            millis = clock() - started,
            cached = response.header("cf-aig-cache-status")?.equals("HIT", ignoreCase = true) == true,
            model = normalizedModel.ifBlank { response.body.text("model").orEmpty() },
            gateway = gateway,
        )
    }

    suspend fun complete(
        messages: List<AiMessage>,
        model: String = DefaultModel,
        system: String? = null,
        maxTokens: Int = 512,
        temperature: Double? = null,
        cacheKey: String? = null,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
        metadata: Map<String, String> = emptyMap(),
        route: AiGatewayRoute = AiGatewayRoute.Compat,
        timeoutSeconds: Long = 60,
    ): AiCompletion {
        val allMessages = buildList {
            system?.trim()?.takeIf(String::isNotEmpty)?.let { add(AiMessage("system", it)) }
            addAll(messages)
        }
        val request = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }
            put("messages", JsonArray(allMessages.map { message ->
                buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                }
            }))
        }
        return complete(
            request = request,
            route = route,
            cacheKey = cacheKey,
            cacheTtlSeconds = cacheTtlSeconds,
            skipCache = skipCache,
            metadata = metadata,
            timeoutSeconds = timeoutSeconds,
        )
    }

    suspend fun complete(
        prompt: String,
        model: String = DefaultModel,
        system: String? = null,
        maxTokens: Int = 512,
        temperature: Double? = null,
        cacheKey: String? = null,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
        metadata: Map<String, String> = emptyMap(),
        route: AiGatewayRoute = AiGatewayRoute.Compat,
        timeoutSeconds: Long = 60,
    ): AiCompletion = complete(
        messages = listOf(AiMessage("user", prompt)),
        model = model,
        system = system,
        maxTokens = maxTokens,
        temperature = temperature,
        cacheKey = cacheKey,
        cacheTtlSeconds = cacheTtlSeconds,
        skipCache = skipCache,
        metadata = metadata,
        route = route,
        timeoutSeconds = timeoutSeconds,
    )

    suspend fun titleRun(
        firstPrompt: String,
        latestMessages: List<String> = emptyList(),
        model: String = DefaultModel,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
    ): AiRunTitle {
        val recent = latestMessages.map(String::trim).filter(String::isNotEmpty).takeLast(6)
        val prompt = buildString {
            appendLine("First prompt:")
            appendLine(firstPrompt.trim())
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("Latest messages:")
                recent.forEach { appendLine("- $it") }
            }
        }.trim()
        val completion = complete(
            prompt = prompt,
            model = model,
            system = TitleSystemPrompt,
            maxTokens = 256,
            temperature = 0.2,
            cacheKey = cacheKeyFor("title", model, firstPrompt.trim(), recent.joinToString("\n")),
            cacheTtlSeconds = cacheTtlSeconds,
            skipCache = skipCache,
            metadata = mapOf("source" to "reaktor-orchestration", "task" to "gateway-titles"),
        )
        val parsed = parseJsonObject(completion.text)
        val fallbackLine = recent.lastOrNull() ?: firstPrompt
        return AiRunTitle(
            title = normalizeShortTitle(parsed?.text("title") ?: lineValue(completion.text, "title"), firstPrompt),
            summary = normalizeOneLine(parsed?.text("summary") ?: lineValue(completion.text, "summary") ?: completion.text, fallbackLine),
            logId = completion.logId,
            cached = completion.cached,
            usage = completion.usage,
        )
    }

    suspend fun summarizeRun(
        firstPrompt: String,
        latestMessages: List<String> = emptyList(),
        model: String = DefaultModel,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
    ): AiRunTitle = titleRun(firstPrompt, latestMessages, model, cacheTtlSeconds, skipCache)

    suspend fun digestChanges(
        inbox: List<String>,
        planChanges: List<String>,
        agent: String? = null,
        model: String = DefaultModel,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
    ): AiChangeDigest {
        val cleanInbox = inbox.map(String::trim).filter(String::isNotEmpty)
        val cleanChanges = planChanges.map(String::trim).filter(String::isNotEmpty)
        val prompt = buildString {
            agent?.trim()?.takeIf(String::isNotEmpty)?.let { appendLine("Agent: $it") }
            appendLine("Inbox messages (${cleanInbox.size}):")
            if (cleanInbox.isEmpty()) appendLine("- none") else cleanInbox.forEach { appendLine("- $it") }
            appendLine("Plan changes (${cleanChanges.size}):")
            if (cleanChanges.isEmpty()) appendLine("- none") else cleanChanges.forEach { appendLine("- $it") }
        }.trim()
        val metadata = buildMap {
            put("source", "reaktor-orchestration")
            put("task", "gateway-digest")
            agent?.trim()?.takeIf(String::isNotEmpty)?.let { put("agent", it) }
        }
        val completion = complete(
            prompt = prompt,
            model = model,
            system = DigestSystemPrompt,
            maxTokens = 384,
            temperature = 0.2,
            cacheKey = cacheKeyFor("digest", model, agent.orEmpty(), cleanInbox.joinToString("\n"), cleanChanges.joinToString("\n")),
            cacheTtlSeconds = cacheTtlSeconds,
            skipCache = skipCache,
            metadata = metadata,
        )
        val parsed = parseJsonObject(completion.text)
        val fallbackSource = cleanInbox.firstOrNull() ?: cleanChanges.firstOrNull() ?: "Plan and inbox updates"
        val highlights = parsed?.strings("highlights")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            .ifEmpty { (cleanInbox + cleanChanges).take(3) }
        return AiChangeDigest(
            title = normalizeShortTitle(parsed?.text("title") ?: lineValue(completion.text, "title"), fallbackSource),
            summary = normalizeOneLine(parsed?.text("summary") ?: lineValue(completion.text, "summary") ?: completion.text, fallbackSource),
            highlights = highlights,
            logId = completion.logId,
            cached = completion.cached,
            usage = completion.usage,
        )
    }

    suspend fun triageItem(
        title: String,
        detail: String = "",
        existingItems: List<AiPlanItemCandidate> = emptyList(),
        model: String = DefaultModel,
        cacheTtlSeconds: Long? = DefaultCacheTtlSeconds,
        skipCache: Boolean = false,
    ): AiItemTriage {
        val knownIds = existingItems.map { it.id }.toSet()
        val prompt = buildString {
            appendLine("New item title: ${title.trim()}")
            if (detail.isNotBlank()) appendLine("New item detail: ${detail.trim()}")
            appendLine("Existing plan items (${existingItems.size}):")
            if (existingItems.isEmpty()) {
                appendLine("- none")
            } else {
                existingItems.forEach { item ->
                    val parentSuffix = item.parent?.let { " parent=$it" }.orEmpty()
                    appendLine("- ${item.id} [${item.kind}, ${item.status}, ${item.priority}$parentSuffix]: ${item.title}")
                }
            }
        }.trim()
        val completion = complete(
            prompt = prompt,
            model = model,
            system = TriageSystemPrompt,
            maxTokens = 384,
            temperature = 0.1,
            cacheKey = cacheKeyFor(
                "triage",
                model,
                title.trim(),
                detail.trim(),
                existingItems.joinToString(",") { "${it.id}:${it.kind}:${it.status}:${it.priority}:${it.parent.orEmpty()}" },
            ),
            cacheTtlSeconds = cacheTtlSeconds,
            skipCache = skipCache,
            metadata = mapOf("source" to "reaktor-orchestration", "task" to "gateway-triage"),
        )
        val parsed = parseJsonObject(completion.text)
        val priority = normalizePriority(parsed?.text("priority") ?: lineValue(completion.text, "priority"))
        val parent = parsed?.text("parent")?.trim()?.takeIf { it in knownIds }
        val after = parsed?.strings("after").orEmpty()
            .map(String::trim)
            .filter { it in knownIds && it != parent }
            .distinct()
        return AiItemTriage(
            priority = priority,
            parent = parent,
            after = after,
            summary = normalizeOneLine(parsed?.text("summary") ?: parsed?.text("reason") ?: completion.text, title),
            logId = completion.logId,
            cached = completion.cached,
            usage = completion.usage,
        )
    }

    companion object {
        const val DefaultBase: String = "https://gateway.ai.cloudflare.com/v1"
        const val DefaultGateway: String = "reaktor"
        const val DefaultModel: String = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
        const val DefaultCacheTtlSeconds: Long = 3_600L

        private val Priorities = listOf("Urgent", "High", "Normal", "Low")

        private const val TitleSystemPrompt =
            "Return only a JSON object {\"title\": \"...\", \"summary\": \"...\"}. " +
                "title must be 3 to 6 words naming the run or thread. summary must be one concise line stating the goal and latest state."

        private const val DigestSystemPrompt =
            "Return only a JSON object {\"title\": \"...\", \"summary\": \"...\", \"highlights\": [\"...\"]}. " +
                "title must be 3 to 6 words. summary must be one concise line summarizing what changed in the inbox and plan."

        private const val TriageSystemPrompt =
            "Return only a JSON object {\"priority\": \"Urgent|High|Normal|Low\", \"parent\": \"id or null\", \"after\": [\"id\"], \"summary\": \"...\"}. " +
                "Choose parent and after only from the listed existing item ids."

        fun compatModel(model: String): String {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return ""
            return when {
                trimmed.startsWith("@cf/") || trimmed.startsWith("@hf/") -> "workers-ai/$trimmed"
                '/' !in trimmed -> "workers-ai/$trimmed"
                else -> trimmed
            }
        }

        fun workersAiModel(model: String): String = model.trim().removePrefix("workers-ai/")

        fun cacheKeyFor(prefix: String, vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEachIndexed { index, part ->
                if (index > 0) digest.update(0)
                digest.update(part.toByteArray(Charsets.UTF_8))
            }
            val hex = digest.digest().take(12).joinToString("") { "%02x".format(it) }
            return "$prefix-$hex"
        }

        internal fun normalizeShortTitle(candidate: String?, fallback: String): String {
            val words = cleanWords(candidate).ifEmpty { cleanWords(fallback) }
            val bounded = when {
                words.size >= 3 -> words.take(6)
                words.isNotEmpty() -> (words + cleanWords(fallback)).distinct().take(6).let { merged ->
                    if (merged.size >= 3) merged else (merged + listOf("work", "run", "task")).take(3)
                }
                else -> listOf("Agent", "work", "run")
            }
            return bounded.joinToString(" ")
        }

        internal fun normalizeOneLine(candidate: String?, fallback: String): String {
            val line = candidate.orEmpty()
                .lineSequence()
                .map { it.trim().trim('"', '\'', '`') }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("{") && !it.startsWith("}") }
                ?: fallback.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
                ?: "No summary available."
            return line.replace(Regex("\\s+"), " ").take(200)
        }

        internal fun normalizePriority(candidate: String?): String {
            val trimmed = candidate?.trim().orEmpty()
            return Priorities.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: "Normal"
        }

        private fun cleanWords(text: String?): List<String> = text.orEmpty()
            .replace(Regex("[`\"'#*\\[\\](){}<>:;,.!?/\\\\|]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        private fun parseJsonObject(text: String): JsonObject? {
            val trimmed = text.trim()
            val unwrapped = trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val slice = if ('{' in unwrapped && '}' in unwrapped) {
                unwrapped.substring(unwrapped.indexOf('{'), unwrapped.lastIndexOf('}') + 1)
            } else {
                unwrapped
            }
            return runCatching { lenientJson.parseToJsonElement(slice) as? JsonObject }.getOrNull()
        }

        private fun lineValue(text: String, key: String): String? = text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf(String::isNotEmpty)

        fun forWorkspace(
            root: File,
            gateway: String = DefaultGateway,
            base: String = DefaultBase,
            clock: () -> Long = System::currentTimeMillis,
        ): AiGatewayClient? {
            val accountId = CloudflareAccounts.of(root) ?: return null
            val login = CloudflareLogin.forWorkspace(root)
            val gatewayCredentials = System.getenv("CF_AIG_TOKEN")?.takeIf(String::isNotBlank)?.let(::CloudflareApiToken) ?: login
            return AiGatewayClient(accountId, gateway, login, gatewayCredentials, base, clock)
        }
    }
}

private fun metadataJson(metadata: Map<String, String>): String =
    buildJsonObject { metadata.entries.take(5).forEach { (key, value) -> put(key, value) } }.toString()

private class CloudflareResponse(val body: JsonObject, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]?.takeIf(String::isNotBlank)
}

private suspend fun requestCloudflareJson(
    http: HttpClient,
    credentials: CloudflareCredentials,
    method: String,
    url: String,
    label: String,
    body: JsonElement? = null,
    headers: (String) -> Map<String, String> = { emptyMap() },
    timeoutSeconds: Long = 30,
): CloudflareResponse {
    suspend fun send(token: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .apply { headers(token).forEach { (name, value) -> header(name, value) } }
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return Transport(http).send(request)
    }
    val response = if (credentials is CloudflareLogin) credentials.authenticated(::send) { it.statusCode() == 401 } else send(credentials.token())
    val parsed = runCatching { lenientJson.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
    if (response.statusCode() !in 200..299) {
        val reason = parsed.objects("errors").firstOrNull()?.text("message")
            ?: parsed.objects("error").firstOrNull()?.text("message")
            ?: parsed?.obj("error")?.text("message")
            ?: parsed?.text("message")
            ?: "HTTP ${response.statusCode()}"
        throw CloudflareFailure("$label: $reason")
    }
    return CloudflareResponse(
        parsed ?: throw CloudflareFailure("$label: unreadable response"),
        response.headers().map().entries.associate { (name, values) -> name.lowercase() to values.firstOrNull().orEmpty() },
    )
}

object CloudflareAiCalls {
    suspend fun execute(call: dev.shibasis.reaktor.cloud.cloudflare.CloudflareAiRequest): String {
        val root = File(call.workspace)
        val login = CloudflareLogin.forWorkspace(root)
        val ai = CloudflareAi(call.account, login)
        return when (call.action) {
            "read" -> lenientJson.encodeToString(AiReading.serializer(), ai.read(login.source))
            "logs" -> lenientJson.encodeToString(ListSerializer(AiGatewayLog.serializer()), ai.logs(requireNotNull(call.gateway) { "Name a gateway" }))
            "create-gateway" -> lenientJson.encodeToString(AiGateway.serializer(), ai.createGateway(requireNotNull(call.gateway) { "Name a gateway" }))
            "complete" -> lenientJson.encodeToString(AiTry.serializer(), attempt(ai, call))
            else -> throw CloudflareFailure("Unknown Cloudflare AI action ${call.action}")
        }
    }

    fun reading(output: String): AiReading = lenientJson.decodeFromString(AiReading.serializer(), output)

    fun logs(output: String): List<AiGatewayLog> = lenientJson.decodeFromString(ListSerializer(AiGatewayLog.serializer()), output)

    fun attempt(output: String): AiTry = lenientJson.decodeFromString(AiTry.serializer(), output)

    private suspend fun attempt(ai: CloudflareAi, call: dev.shibasis.reaktor.cloud.cloudflare.CloudflareAiRequest): AiTry {
        val model = requireNotNull(call.model) { "Name a model" }
        val request = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("messages", JsonArray(listOfNotNull(
                call.system?.takeIf(String::isNotBlank)?.let { buildJsonObject { put("role", "system"); put("content", it) } },
                buildJsonObject { put("role", "user"); put("content", call.prompt.orEmpty()) },
            )))
        }
        return runCatching { ai.complete(request, call.gateway, mapOf("source" to "hangar-try")) }.fold(
            onSuccess = { completion ->
                val message = completion.body.objects("choices").firstOrNull().obj("message")
                val usage = completion.body.obj("usage")
                AiTry(model, call.gateway, message.text("content").orEmpty(), message.text("reasoning_content"),
                    usage.long("prompt_tokens") ?: 0, usage.long("completion_tokens") ?: 0, usage.number("neurons"), completion.millis, completion.logId)
            },
            onFailure = { AiTry(model, call.gateway, "", null, 0, 0, null, 0, null, it.message ?: "The call failed") },
        )
    }
}
