package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.UsageScope
import dev.shibasis.reaktor.conductor.UsageSummary
import dev.shibasis.reaktor.conductor.forTurn
import dev.shibasis.reaktor.conductor.summarizeUsage
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal data class HarnessUsageRequest(
    val runtime: RuntimeKind,
    val sessionId: String,
    val requestId: String,
    val at: Long,
    val directory: String?,
    val subagent: Boolean,
    val model: String?,
    val usage: AgentUsage,
) {
    fun json(): JsonObject = buildJsonObject {
        put("runtime", runtime.name); put("sessionId", sessionId); put("requestId", requestId)
        put("at", Instant.ofEpochMilli(at).toString()); put("directory", directory)
        put("subagent", subagent); put("model", model)
        put("usage", ConductorJson.encodeToJsonElement(AgentUsage.serializer(), usage))
    }
}

internal class HarnessUsageLedger {
    private val requests = linkedMapOf<Pair<RuntimeKind, String>, HarnessUsageRequest>()
    private val allowances = linkedMapOf<String, JsonObject>()
    private var malformedLines = 0
    private var unreadableFiles = 0
    private var unidentifiableRequests = 0
    private var filesRead = 0

    fun read(home: File, since: Long, until: Long, directory: File? = null): JsonObject {
        require(since < until) { "--since must precede --until" }
        requests.clear(); allowances.clear()
        malformedLines = 0; unreadableFiles = 0; unidentifiableRequests = 0; filesRead = 0
        listOf(".codex/sessions", ".codex/archived_sessions", ".claude/projects").forEach { path ->
            val root = File(home, path)
            if (root.isDirectory) root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && it.extension == "jsonl" && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file -> readFile(file, path.startsWith(".codex"), since, until) }
        }
        val cwd = directory?.canonicalPath
        val selected = rows(since, until, cwd)
        return buildJsonObject {
            put("schemaVersion", 1); put("since", Instant.ofEpochMilli(since).toString())
            put("untilExclusive", Instant.ofEpochMilli(until).toString()); put("cwdRoot", cwd)
            put("filesRead", filesRead); put("malformedLines", malformedLines)
            put("unreadableFiles", unreadableFiles); put("unidentifiableRequests", unidentifiableRequests)
            put("usage", summary(selected))
            putJsonArray("groups") {
                selected.groupBy { it.runtime to it.subagent }.toSortedMap(compareBy({ it.first.name }, { it.second })).forEach { (group, rows) ->
                    add(buildJsonObject {
                        put("runtime", group.first.name); put("subagent", group.second)
                        put("sessions", rows.map { it.sessionId }.distinct().size); put("usage", summary(rows))
                        val inputs = rows.mapNotNull { it.usage.inputTokens }
                        put("meanInputPerRequest", if (inputs.isEmpty()) null else inputs.average())
                        put("peakInputPerRequest", inputs.maxOrNull())
                    })
                }
            }
            putJsonArray("codexWeeklyAllowance") { allowances.toSortedMap().values.forEach(::add) }
        }
    }

    fun rows(since: Long, until: Long, cwd: String? = null): List<HarnessUsageRequest> {
        val scope = cwd?.let { File(it).canonicalFile.toPath() }
        val directories = mutableMapOf<String, Path?>()
        return requests.values.filter { it.at >= since && it.at < until }
            .filter { request ->
                scope == null || request.directory?.let { path ->
                    directories.getOrPut(path) {
                        runCatching { File(path).takeIf { it.isAbsolute }?.canonicalFile?.toPath() }.getOrNull()
                    }?.startsWith(scope) == true
                } == true
            }
            .sortedWith(compareBy({ it.at }, { it.runtime.name }, { it.requestId }))
    }

    private fun summary(rows: List<HarnessUsageRequest>): JsonElement = ConductorJson.encodeToJsonElement(
        UsageSummary.serializer(), summarizeUsage(rows.map { it.usage }),
    )

    private fun readFile(file: File, codex: Boolean, since: Long, until: Long) {
        var meta: JsonObject? = null
        var model: String? = null
        var previous: AgentUsage? = null
        var previousCounter: String? = null
        var counterEpoch = 0
        filesRead++
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val record = runCatching { ConductorJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (record == null) { malformedLines++; return@forEachLine }
                if (!codex) { claude(record, file); return@forEachLine }
                val payload = record.obj("payload") ?: return@forEachLine
                when (record.text("type")) {
                    "session_meta" -> meta = payload
                    "turn_context" -> model = payload.text("model")
                    "event_msg" -> if (payload.text("type") == "token_count") {
                        val at = epoch(record.text("timestamp"))
                        if (at != null && at >= since && at < until) allowance(payload, at)
                        val info = payload.obj("info") ?: return@forEachLine
                        val total = info.obj("total_token_usage")?.codexUsage(UsageScope.ProviderSessionTotal)
                        val last = info.obj("last_token_usage")?.codexUsage()
                        if (total == null && last == null) return@forEachLine
                        val reset = total?.inputTokens?.let { current -> previous?.inputTokens?.let { current < it } } == true
                        if (reset) counterEpoch++
                        val counter = total?.let { listOf(it.inputTokens, it.outputTokens, it.cachedInputTokens, it.cacheWriteInputTokens, it.reasoningOutputTokens).joinToString(":") }
                        val duplicate = counter != null && counter == previousCounter && !reset
                        val usage = last ?: total!!.forTurn(previous, freshSession = previous == null && meta?.text("forked_from_id") == null)
                        previous = total ?: previous
                        previousCounter = counter
                        if (duplicate) return@forEachLine
                        val id = meta?.text("id")
                        val started = epoch(meta?.text("timestamp"))
                        if (id == null || at == null) { unidentifiableRequests++; return@forEachLine }
                        // Forks replay parent events; the first new total can also include inherited usage.
                        if (started != null && at < started) return@forEachLine
                        val source = meta?.get("source")
                        add(HarnessUsageRequest(RuntimeKind.Codex, id, "$id:$counterEpoch:${counter ?: at}", at,
                            meta?.text("cwd"), source is JsonObject && source["subagent"] != null,
                            model, usage))
                    }
                }
            }
        } catch (_: java.io.IOException) { unreadableFiles++ }
    }

    private fun claude(record: JsonObject, file: File) {
        if (record.text("type") != "assistant") return
        val message = record.obj("message") ?: return
        if (message.text("model") == "<synthetic>") return
        val id = message.text("id")
        val at = epoch(record.text("timestamp"))
        if (id == null || at == null) { unidentifiableRequests++; return }
        val raw = message.obj("usage")
        val fresh = raw?.count("input_tokens")
        val cached = raw?.count("cache_read_input_tokens")
        val written = raw?.count("cache_creation_input_tokens")
        val subagent = record.text("agentId") != null || file.parentFile.name == "subagents"
        val parentId = record.text("sessionId") ?: file.parentFile.parentFile.name
        val sessionId = if (subagent) "$parentId:${record.text("agentId") ?: file.nameWithoutExtension}"
            else record.text("sessionId") ?: file.nameWithoutExtension
        add(HarnessUsageRequest(RuntimeKind.ClaudeCode, sessionId,
            id, at, record.text("cwd"), subagent,
            message.text("model"), AgentUsage(
                inputTokens = if (fresh != null && cached != null && written != null) fresh + cached + written else null,
                cachedInputTokens = cached, cacheWriteInputTokens = written, outputTokens = raw?.count("output_tokens"),
                reasoningOutputTokens = raw?.obj("output_tokens_details")?.count("thinking_tokens"),
            )))
    }

    private fun add(request: HarnessUsageRequest) {
        val key = request.runtime to request.requestId
        val old = requests[key]
        if (old == null) { requests[key] = request; return }
        fun merge(a: Long?, b: Long?): Long? = listOfNotNull(a, b).maxOrNull()
        requests[key] = old.copy(at = minOf(old.at, request.at), usage = AgentUsage(
            inputTokens = merge(old.usage.inputTokens, request.usage.inputTokens),
            cachedInputTokens = merge(old.usage.cachedInputTokens, request.usage.cachedInputTokens),
            cacheWriteInputTokens = merge(old.usage.cacheWriteInputTokens, request.usage.cacheWriteInputTokens),
            outputTokens = merge(old.usage.outputTokens, request.usage.outputTokens),
            reasoningOutputTokens = merge(old.usage.reasoningOutputTokens, request.usage.reasoningOutputTokens),
        ))
    }

    private fun allowance(payload: JsonObject, at: Long) {
        val limits = payload.obj("rate_limits") ?: return
        listOf("primary", "secondary").forEach { slot ->
            val window = limits.obj(slot) ?: return@forEach
            if (window.count("window_minutes") != 10080L) return@forEach
            val reset = window.count("resets_at") ?: return@forEach
            val used = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..100.0 } ?: return@forEach
            val id = limits.text("limit_id") ?: "codex"
            val previous = allowances[id]?.count("observedAtEpochMillis") ?: Long.MIN_VALUE
            if (at < previous) return@forEach
            allowances[id] = buildJsonObject {
                put("limitId", id); put("usedPercent", used); put("resetsAtEpochSeconds", reset)
                put("observedAtEpochMillis", at)
            }
        }
    }

    private fun JsonObject.codexUsage(scope: UsageScope = UsageScope.Turn) = AgentUsage(
        inputTokens = count("input_tokens"), cachedInputTokens = count("cached_input_tokens"),
        cacheWriteInputTokens = count("cache_write_input_tokens"), outputTokens = count("output_tokens"),
        reasoningOutputTokens = count("reasoning_output_tokens"), scope = scope,
    )

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    private fun epoch(value: String?) = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

internal fun usageCli(args: List<String>) {
    if (args == listOf("--help")) {
        println("usage [--since <ISO-date-or-instant>] [--until <ISO-date-or-instant>] [--dir <cwd-root>] [--requests <new.jsonl>]\n" +
            "Dates start at midnight UTC; --until is exclusive. Defaults to the last seven days.\n" +
            "--dir filters request working directories, not files touched. Allowance is the latest account-wide snapshot per limit in the interval.\n" +
            "--requests exports usage metadata only. Existing files are never overwritten.")
        return
    }
    val options = mutableMapOf<String, String>()
    require(args.size % 2 == 0) { "Every usage option needs a value" }
    args.chunked(2).forEach { (key, value) ->
        require(key in setOf("--since", "--until", "--dir", "--requests")) { "Unknown usage option: $key" }
        require(options.put(key, value) == null) { "Duplicate usage option: $key" }
    }
    fun time(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
        LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val until = options["--until"]?.let(::time) ?: System.currentTimeMillis()
    val since = options["--since"]?.let(::time) ?: until - 7 * 86_400_000L
    val directory = options["--dir"]?.let { File(it).canonicalFile.also { root -> require(root.isDirectory) { "Not a directory: $root" } } }
    val output = options["--requests"]?.let { File(it).canonicalFile.also { file -> require(!file.exists()) { "Already exists: $file" } } }
    val ledger = HarnessUsageLedger()
    val report = ledger.read(File(System.getProperty("user.home")), since, until, directory)
    output?.let { file ->
        file.parentFile.mkdirs()
        check(file.createNewFile()) { "Already exists: $file" }
        file.bufferedWriter().use { writer -> ledger.rows(since, until, directory?.path).forEach {
            writer.append(ConductorJson.encodeToString(JsonObject.serializer(), it.json())).append('\n')
        } }
    }
    println(ConductorJson.encodeToString(JsonObject.serializer(), report))
}
