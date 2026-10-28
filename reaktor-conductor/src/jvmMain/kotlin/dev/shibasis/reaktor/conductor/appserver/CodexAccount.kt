package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.EffortSupport
import dev.shibasis.reaktor.conductor.ModelChoice
import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.worklog.RateLimits
import dev.shibasis.reaktor.conductor.worklog.RateWindow
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Account-wide buckets are deliberately not attributed to individual models. */
data class CodexUsageBucket(
    val id: String,
    val name: String,
    val limits: RateLimits,
    val model: String? = null,
    val creditBalance: String? = null,
    val unlimitedCredits: Boolean? = null,
    val limitReached: String? = null,
)

data class CodexAccountSnapshot(
    val models: List<ModelChoice>?,
    val buckets: List<CodexUsageBucket>?,
    val at: Long,
    val defaultModel: String? = null,
    val modelsError: String? = null,
    val usageError: String? = null,
    val ordinaryUsageAllowed: Boolean? = null,
)

/** Read-only RPCs: never creates a thread, starts inference, or consumes a reset credit. */
suspend fun readCodexAccount(binary: String = "codex", directory: File): CodexAccountSnapshot = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(binary, "app-server").directory(directory)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rpc = JsonRpcStdio(process.inputStream.bufferedReader(), process.outputStream.writer(), scope)
    try {
        withTimeoutOrNull(20_000) {
            rpc.request("initialize", buildJsonObject {
                putJsonObject("clientInfo") { put("name", "hangar"); put("title", "Hangar"); put("version", "1") }
            })
            rpc.notify("initialized")
            val models = async {
                accountResult {
                    val rows = mutableListOf<JsonElement>()
                    var cursor: String? = null
                    val seen = mutableSetOf<String>()
                    do {
                        val page = rpc.request("model/list", buildJsonObject {
                            put("includeHidden", false)
                            cursor?.let { put("cursor", it) }
                        })
                        rows += page["data"]?.jsonArray.orEmpty()
                        cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
                        check(cursor == null || seen.add(cursor)) { "Model catalog repeated its cursor" }
                    } while (cursor != null)
                    JsonArray(rows)
                }
            }
            val usage = async { accountResult { rpc.request("account/rateLimits/read") } }
            val catalog = models.await()
            val limits = usage.await()
            parseCodexAccount(catalog.getOrNull(), limits.getOrNull(), System.currentTimeMillis()).copy(
                modelsError = catalog.exceptionOrNull()?.message,
                usageError = limits.exceptionOrNull()?.message,
            )
        } ?: error("Codex account read timed out after 20 seconds")
    } finally {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        rpc.close()
        scope.cancel()
    }
}

private suspend fun <T> accountResult(block: suspend () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }

internal fun parseCodexAccount(models: JsonArray?, usage: JsonObject?, at: Long): CodexAccountSnapshot {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    val visible = models?.mapNotNull { it as? JsonObject }?.filter { it["hidden"]?.jsonPrimitive?.booleanOrNull != true }
    val choices = visible?.mapNotNull { row ->
        val id = row.text("model")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val efforts = (row["supportedReasoningEfforts"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.text("reasoningEffort")?.takeIf(String::isNotBlank)?.let(::NativeEffort)
        }
        ModelChoice(id, row.text("displayName") ?: id, EffortSupport(efforts,
            row.text("defaultReasoningEffort")?.takeIf(String::isNotBlank)?.let(::NativeEffort), "codex model/list"))
    }?.distinctBy { it.id }
    val buckets = (usage?.get("rateLimitsByLimitId") as? JsonObject)?.takeIf { it.isNotEmpty() }
        ?: (usage?.get("rateLimits") as? JsonObject)?.let { mapOf((it.text("limitId") ?: "codex") to it) }
    return CodexAccountSnapshot(choices, buckets?.mapNotNull { (id, element) ->
        val bucket = element as? JsonObject ?: return@mapNotNull null
        val windows = listOf("primary", "secondary").mapNotNull window@{ key ->
            val window = bucket[key] as? JsonObject ?: return@window null
            val used = (window["usedPercent"] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: return@window null
            val seconds = (window["resetsAt"] as? JsonPrimitive)?.longOrNull
            RateWindow(used, (window["windowDurationMins"] as? JsonPrimitive)?.longOrNull,
                seconds?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000))
        }
        val credits = bucket["credits"] as? JsonObject
        CodexUsageBucket(id, bucket.text("limitName") ?: id, RateLimits(windows, bucket.text("planType"), at),
            bucket.text("normalModelSlug"), credits?.text("balance"),
            (credits?.get("unlimited") as? JsonPrimitive)?.booleanOrNull, bucket.text("rateLimitReachedType"))
    }, at, visible?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }?.text("model"),
        ordinaryUsageAllowed = (usage?.get("ordinaryUsageAllowed") as? JsonPrimitive)?.booleanOrNull)
}
