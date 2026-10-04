package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

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

data class AiCompletion(val body: JsonObject, val logId: String?, val millis: Long)

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

class CloudflareLogin(val source: String, private val credentials: CloudflareCredentials) : CloudflareCredentials by credentials {
    companion object {
        const val TokenFile: String = "config/cloudflare.token"

        fun forWorkspace(root: File): CloudflareLogin {
            System.getenv("CLOUDFLARE_API_TOKEN")?.takeIf { it.isNotBlank() }
                ?.let { return CloudflareLogin("CLOUDFLARE_API_TOKEN", CloudflareApiToken(it)) }
            listOf(TokenFile, "config/mcp/cloudflare.token").firstNotNullOfOrNull { path ->
                File(root, path).takeIf(File::isFile)?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?.let { CloudflareLogin(path, CloudflareApiToken(it)) }
            }?.let { return it }
            return CloudflareLogin("Wrangler sign-in", WranglerLogin(refresh = { CloudInventoryReads.refreshWrangler(root) }))
        }
    }
}

object CloudflareAccounts {
    private val AccountId = Regex("""account_id"?\s*[:=]\s*"([0-9a-f]{32})"""")
    private val Skipped = setOf("node_modules", "build", "dist", ".git", ".gradle", ".claude", ".wrangler")

    fun of(root: File): String? = System.getenv("CLOUDFLARE_ACCOUNT_ID")?.takeIf { it.isNotBlank() }
        ?: root.walkTopDown().maxDepth(4).onEnter { it.name !in Skipped }
            .filter { it.isFile && it.name.startsWith("wrangler.") && it.extension in setOf("json", "jsonc", "toml") }
            .mapNotNull { file -> AccountId.find(file.readText())?.groupValues?.get(1) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

class CloudflareAi(
    private val accountId: String,
    private val credentials: CloudflareCredentials,
    private val base: String = "https://api.cloudflare.com/client/v4",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

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
        call("GET", "/accounts/$accountId/ai-gateway/gateways?per_page=100").body.objects("result").map(::gateway).sortedBy { it.id }

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

    suspend fun createGateway(id: String): AiGateway = gateway(
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
            if (metadata.isNotEmpty()) put("cf-aig-metadata", buildJsonObject { metadata.entries.take(5).forEach { (key, value) -> put(key, value) } }.toString())
        }
        val started = clock()
        val response = call("POST", "/accounts/$accountId/ai/v1/chat/completions", request, headers, timeoutSeconds = 180)
        return AiCompletion(response.body, response.headers["cf-aig-log-id"], clock() - started)
    }

    private fun gateway(item: JsonObject) = AiGateway(
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

    private class Response(val body: JsonObject, val headers: Map<String, String>)

    private suspend fun call(method: String, path: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = 30): Response {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Bearer ${credentials.token()}")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val parsed = runCatching { cloudJson.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val reason = parsed.objects("errors").firstOrNull()?.text("message") ?: parsed?.obj("error")?.text("message") ?: "HTTP ${response.statusCode()}"
            throw CloudflareFailure("${path.substringBefore('?').substringAfter("/accounts/$accountId")}: $reason")
        }
        return Response(parsed ?: throw CloudflareFailure("${path.substringBefore('?')}: unreadable response"),
            response.headers().map().mapValues { it.value.firstOrNull().orEmpty() })
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

object CloudflareAiCalls {
    suspend fun execute(call: dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudflareAiCall): String {
        val root = File(call.workspace)
        val login = CloudflareLogin.forWorkspace(root)
        val ai = CloudflareAi(call.account, login)
        return when (call.action) {
            "read" -> cloudJson.encodeToString(AiReading.serializer(), ai.read(login.source))
            "logs" -> cloudJson.encodeToString(ListSerializer(AiGatewayLog.serializer()), ai.logs(requireNotNull(call.gateway) { "Name a gateway" }))
            "create-gateway" -> cloudJson.encodeToString(AiGateway.serializer(), ai.createGateway(requireNotNull(call.gateway) { "Name a gateway" }))
            "complete" -> cloudJson.encodeToString(AiTry.serializer(), attempt(ai, call))
            else -> throw CloudflareFailure("Unknown Cloudflare AI action ${call.action}")
        }
    }

    fun reading(output: String): AiReading = cloudJson.decodeFromString(AiReading.serializer(), output)

    fun logs(output: String): List<AiGatewayLog> = cloudJson.decodeFromString(ListSerializer(AiGatewayLog.serializer()), output)

    fun attempt(output: String): AiTry = cloudJson.decodeFromString(AiTry.serializer(), output)

    private suspend fun attempt(ai: CloudflareAi, call: dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudflareAiCall): AiTry {
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
