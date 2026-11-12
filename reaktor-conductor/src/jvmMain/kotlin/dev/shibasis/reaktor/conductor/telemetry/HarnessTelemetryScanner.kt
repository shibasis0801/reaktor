package dev.shibasis.reaktor.conductor.telemetry

import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class ScannedTelemetryRequest(
    val runtime: RuntimeKind,
    val sessionId: String,
    val requestId: String,
    val at: Long,
    val directory: String?,
    val subagent: Boolean,
    val model: String?,
    val provider: ProviderTelemetry,
    val sample: TelemetrySample,
    val usage: AgentUsage,
)

data class HarnessTelemetryScan(
    val requests: List<ScannedTelemetryRequest>,
    val allRequests: List<ScannedTelemetryRequest>,
    val codexWeeklyAllowances: List<CodexTelemetry.WeeklyAllowance>,
    val filesRead: Int,
    val malformedLines: Int,
    val unreadableFiles: Int,
    val unidentifiableRequests: Int,
) {
    fun filterRows(since: Long, until: Long, cwd: String? = null): List<ScannedTelemetryRequest> =
        HarnessTelemetryScanner.filterRows(allRequests, since, until, cwd)
}

object HarnessTelemetryScanner {
    private val antigravityCaches = listOf(
        ".gemini/antigravity/cache/last_conversations.json",
        ".gemini/antigravity-cli/cache/last_conversations.json",
    )

    private val antigravityBrains = listOf(
        ".gemini/antigravity/brain",
        ".gemini/antigravity-cli/brain",
    )

    private val pathArgumentKeys = listOf(
        "Cwd", "DirectoryPath", "SearchDirectory", "TargetFile", "AbsolutePath", "Path", "File",
    )

    fun scan(
        home: File,
        since: Long,
        until: Long,
        directory: File? = null,
        runtimes: Set<RuntimeKind> = setOf(RuntimeKind.ClaudeCode, RuntimeKind.Codex, RuntimeKind.Gemini),
        onlyModifiedSince: Long? = null,
        resolveGeminiModel: (String) -> String? = { null },
    ): HarnessTelemetryScan {
        require(since < until) { "--since must precede --until" }
        val state = ScanState(since, until, onlyModifiedSince, resolveGeminiModel)
        if (RuntimeKind.Codex in runtimes) {
            listOf(".codex/sessions", ".codex/archived_sessions").forEach { relative ->
                state.walkJsonlFiles(File(home, relative)) { file -> state.readCodexFile(file) }
            }
        }
        if (RuntimeKind.ClaudeCode in runtimes) {
            state.walkJsonlFiles(File(home, ".claude/projects")) { file -> state.readClaudeFile(file) }
        }
        if (RuntimeKind.Gemini in runtimes) {
            state.readGeminiBrains(home)
        }
        val all = state.requests.values.toList()
        val selected = filterRows(all, since, until, directory?.canonicalPath)
        return HarnessTelemetryScan(
            requests = selected,
            allRequests = all,
            codexWeeklyAllowances = state.allowances.toSortedMap().values.toList(),
            filesRead = state.filesRead,
            malformedLines = state.malformedLines,
            unreadableFiles = state.unreadableFiles,
            unidentifiableRequests = state.unidentifiableRequests,
        )
    }

    fun filterRows(
        rows: Collection<ScannedTelemetryRequest>,
        since: Long,
        until: Long,
        cwd: String? = null,
    ): List<ScannedTelemetryRequest> {
        val scope = cwd?.let { File(it).canonicalFile.toPath() }
        val resolvedDirectories = mutableMapOf<String, Path?>()
        return rows.asSequence()
            .filter { it.at >= since && it.at < until }
            .filter { request ->
                scope == null || request.directory?.let { path ->
                    resolvedDirectories.getOrPut(path) {
                        runCatching { File(path).takeIf { it.isAbsolute }?.canonicalFile?.toPath() }.getOrNull()
                    }?.startsWith(scope) == true
                } == true
            }
            .sortedWith(compareBy({ it.at }, { it.runtime.name }, { it.requestId }))
            .toList()
    }

    fun readAntigravityWorkspaceMap(home: File): Map<String, String> =
        antigravityCaches.flatMap { relative ->
            val file = File(home, relative)
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) emptyList()
            else runCatching { ConductorJson.parseToJsonElement(file.readText()) as? JsonObject }
                .getOrNull()
                .orEmpty()
                .entries
                .mapNotNull { (folder, id) -> (id as? JsonPrimitive)?.contentOrNull?.let { it to folder } }
        }.toMap()

    private class ScanState(
        private val since: Long,
        private val until: Long,
        private val onlyModifiedSince: Long?,
        private val resolveGeminiModel: (String) -> String?,
    ) {
        val requests = linkedMapOf<Pair<RuntimeKind, String>, ScannedTelemetryRequest>()
        val allowances = linkedMapOf<String, CodexTelemetry.WeeklyAllowance>()
        var filesRead = 0
        var malformedLines = 0
        var unreadableFiles = 0
        var unidentifiableRequests = 0

        fun walkJsonlFiles(root: File, onFile: (File) -> Unit) {
            if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return
            root.walkTopDown()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { file ->
                    file.isFile &&
                        file.extension == "jsonl" &&
                        !Files.isSymbolicLink(file.toPath()) &&
                        (onlyModifiedSince == null || file.lastModified() >= onlyModifiedSince)
                }
                .forEach(onFile)
        }

        fun readClaudeFile(file: File) {
            filesRead++
            val fileSubagent = file.parentFile?.name == "subagents"
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val record = parseJsonObject(line) ?: return@forEachLine
                    if (record.str("type") != "assistant") return@forEachLine
                    val parsed = ClaudeTelemetry.parseRecord(record, subagent = fileSubagent) as? ProviderTelemetry.Claude
                        ?: return@forEachLine
                    val id = parsed.requestId
                    val at = parsed.timestampMillis
                    if (id == null || at == null) {
                        unidentifiableRequests++
                        return@forEachLine
                    }
                    val subagent = parsed.subagent
                    val parentId = record.str("sessionId") ?: file.parentFile?.parentFile?.name ?: file.nameWithoutExtension
                    val sessionId = if (subagent) {
                        "$parentId:${record.str("agentId") ?: file.nameWithoutExtension}"
                    } else {
                        record.str("sessionId") ?: file.nameWithoutExtension
                    }
                    val updated = parsed.copy(subagent = subagent)
                    upsert(
                        ScannedTelemetryRequest(
                            runtime = RuntimeKind.ClaudeCode,
                            sessionId = sessionId,
                            requestId = id,
                            at = at,
                            directory = record.str("cwd"),
                            subagent = subagent,
                            model = updated.model,
                            provider = updated,
                            sample = updated.normalize(),
                            usage = updated.toAgentUsage(),
                        )
                    )
                }
            } catch (_: IOException) {
                unreadableFiles++
            }
        }

        fun readCodexFile(file: File) {
            filesRead++
            val cursor = CodexTelemetry.SessionCursor()
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val record = parseJsonObject(line) ?: return@forEachLine
                    CodexTelemetry.parseWeeklyAllowances(record).forEach { allowance ->
                        if (allowance.observedAtEpochMillis in since until until) {
                            val prior = allowances[allowance.limitId]?.observedAtEpochMillis ?: Long.MIN_VALUE
                            if (allowance.observedAtEpochMillis >= prior) {
                                allowances[allowance.limitId] = allowance
                            }
                        }
                    }
                    val event = cursor.ingest(record) ?: return@forEachLine
                    if (event.unidentifiable) {
                        unidentifiableRequests++
                        return@forEachLine
                    }
                    val codex = event.record
                    if (codex.compacted) return@forEachLine
                    val requestId = codex.requestId
                    val at = codex.timestampMillis
                    val sessionId = event.sessionId
                    if (requestId == null || at == null || sessionId == null) {
                        unidentifiableRequests++
                        return@forEachLine
                    }
                    upsert(
                        ScannedTelemetryRequest(
                            runtime = RuntimeKind.Codex,
                            sessionId = sessionId,
                            requestId = requestId,
                            at = at,
                            directory = event.cwd,
                            subagent = codex.subagent,
                            model = codex.model,
                            provider = codex,
                            sample = codex.normalize(),
                            usage = codex.toAgentUsage(),
                        )
                    )
                }
            } catch (_: IOException) {
                unreadableFiles++
            }
        }

        fun readGeminiBrains(home: File) {
            val workspaceByConversation = readAntigravityWorkspaceMap(home)
            val transcripts = mutableListOf<GeminiTranscript>()
            antigravityBrains.forEach { relative ->
                val brainRoot = File(home, relative)
                if (!brainRoot.isDirectory || Files.isSymbolicLink(brainRoot.toPath())) return@forEach
                val children = brainRoot.listFiles().orEmpty()
                for (conversationDir in children) {
                    if (!conversationDir.isDirectory || Files.isSymbolicLink(conversationDir.toPath())) continue
                    val systemDir = File(conversationDir, ".system_generated")
                    val logsDir = File(systemDir, "logs")
                    val transcript = File(logsDir, "transcript.jsonl")
                    if (Files.isSymbolicLink(systemDir.toPath()) ||
                        Files.isSymbolicLink(logsDir.toPath()) ||
                        !transcript.isFile ||
                        Files.isSymbolicLink(transcript.toPath())
                    ) continue
                    if (onlyModifiedSince != null && transcript.lastModified() < onlyModifiedSince) continue
                    readGeminiTranscript(conversationDir.name, transcript, workspaceByConversation[conversationDir.name])
                        ?.let(transcripts::add)
                }
            }
            val parentByChild = linkedMapOf<String, GeminiTranscript>()
            for (transcript in transcripts) {
                for (childId in transcript.childConversationIds) {
                    if (childId != transcript.conversationId) {
                        parentByChild.putIfAbsent(childId, transcript)
                    }
                }
            }
            for (transcript in transcripts) {
                val parent = parentByChild[transcript.conversationId]
                val isSubagent = parent != null
                val sessionId = if (parent != null) "${parent.conversationId}:${transcript.conversationId}" else transcript.conversationId
                val directory = transcript.directory ?: parent?.directory
                for (step in transcript.steps) {
                    val updated = step.copy(
                        requestId = "${transcript.conversationId}:${step.stepIndex}",
                        subagent = isSubagent,
                    )
                    upsert(
                        ScannedTelemetryRequest(
                            runtime = RuntimeKind.Gemini,
                            sessionId = sessionId,
                            requestId = requireNotNull(updated.requestId),
                            at = requireNotNull(updated.timestampMillis),
                            directory = directory,
                            subagent = isSubagent,
                            model = updated.model,
                            provider = updated,
                            sample = updated.normalize(),
                            usage = updated.toAgentUsage(),
                        )
                    )
                }
            }
        }

        private fun readGeminiTranscript(
            conversationId: String,
            file: File,
            mappedWorkspace: String?,
        ): GeminiTranscript? {
            filesRead++
            val desktop = "/.gemini/antigravity/brain/" in file.invariantSeparatorsPath
            val defaultModel = if (desktop) resolveGeminiModel(conversationId) else "gemini-4-argon-high"
            val steps = linkedMapOf<Int, ProviderTelemetry.Gemini>()
            val childIds = linkedSetOf<String>()
            val observedPaths = linkedListPaths()
            var expectingSubagentReply = false
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val record = parseJsonObject(line) ?: return@forEachLine
                    extractGeminiPathsAndSubagents(record, observedPaths, childIds).let { invoked ->
                        if (expectingSubagentReply) {
                            childIds.addAll(GeminiTelemetry.extractSubagentIdsFromStepResult(record))
                        }
                        expectingSubagentReply = invoked
                    }
                    if (record.str("type") != "PLANNER_RESPONSE") return@forEachLine
                    val parsed = GeminiTelemetry.parseRecord(record, defaultModel, subagent = false) as? ProviderTelemetry.Gemini
                        ?: return@forEachLine
                    val index = parsed.stepIndex
                    val at = parsed.timestampMillis
                    if (index == null || at == null) {
                        unidentifiableRequests++
                        return@forEachLine
                    }
                    val existing = steps[index]
                    steps[index] = if (existing == null) parsed else mergeGemini(existing, parsed)
                }
            } catch (_: IOException) {
                unreadableFiles++
                return null
            }
            val directory = mappedWorkspace ?: inferGeminiDirectory(observedPaths)
            return GeminiTranscript(
                conversationId = conversationId.lowercase(),
                directory = directory,
                steps = steps.values.toList(),
                childConversationIds = childIds,
            )
        }

        private fun parseJsonObject(line: String): JsonObject? {
            val parsed = runCatching { ConductorJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (parsed == null) malformedLines++
            return parsed
        }

        private fun upsert(incoming: ScannedTelemetryRequest) {
            val key = incoming.runtime to incoming.requestId
            val existing = requests[key]
            if (existing == null) {
                requests[key] = incoming
                return
            }
            val mergedProvider = mergeProvider(existing.provider, incoming.provider)
            val mergedUsage = mergeAgentUsage(existing.usage, incoming.usage)
            requests[key] = existing.copy(
                at = minOf(existing.at, incoming.at),
                directory = existing.directory ?: incoming.directory,
                subagent = existing.subagent || incoming.subagent,
                model = incoming.model ?: existing.model,
                provider = mergedProvider,
                sample = mergedProvider.normalize(),
                usage = mergedUsage,
            )
        }
    }

    private data class GeminiTranscript(
        val conversationId: String,
        val directory: String?,
        val steps: List<ProviderTelemetry.Gemini>,
        val childConversationIds: Set<String>,
    )

    private fun linkedListPaths(): MutableList<String> = mutableListOf()

    private fun extractGeminiPathsAndSubagents(
        record: JsonObject,
        observedPaths: MutableList<String>,
        childIds: MutableSet<String>,
    ): Boolean {
        var invokedSubagent = false
        val calls = record["tool_calls"] as? JsonArray ?: return false
        for (item in calls) {
            val call = item as? JsonObject ?: continue
            val name = call.str("name")
            if (name == "invoke_subagent") {
                invokedSubagent = true
                extractUuids(call["result"] ?: call["output"]).forEach(childIds::add)
            }
            val args = parseArgumentsObject(call["args"]) ?: continue
            for (key in pathArgumentKeys) {
                val candidate = args.str(key)?.takeIf { it.startsWith("/") && !it.contains("/.gemini/") } ?: continue
                observedPaths += candidate
            }
        }
        return invokedSubagent
    }

    private fun parseArgumentsObject(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> element.contentOrNull?.let { text ->
            runCatching { ConductorJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        else -> null
    }

    private fun inferGeminiDirectory(paths: List<String>): String? {
        for (raw in paths) {
            val candidate = runCatching {
                val file = File(raw)
                if (file.isDirectory) file.path
                else if (file.exists()) file.parentFile?.path
                else if (file.extension.isNotEmpty()) file.parentFile?.path ?: file.path
                else file.path
            }.getOrNull()
            if (!candidate.isNullOrBlank()) return candidate
        }
        return null
    }

    private fun mergeProvider(first: ProviderTelemetry, second: ProviderTelemetry): ProviderTelemetry = when {
        first is ProviderTelemetry.Claude && second is ProviderTelemetry.Claude -> ClaudeTelemetry.mergeHighWater(first, second)
        first is ProviderTelemetry.Gemini && second is ProviderTelemetry.Gemini -> mergeGemini(first, second)
        first is ProviderTelemetry.Codex && second is ProviderTelemetry.Codex -> first.copy(
            timestampMillis = listOfNotNull(first.timestampMillis, second.timestampMillis).minOrNull(),
            model = second.model ?: first.model,
            effort = second.effort ?: first.effort,
            inclusiveInputTokens = listOfNotNull(first.inclusiveInputTokens, second.inclusiveInputTokens).maxOrNull(),
            cachedInputTokens = listOfNotNull(first.cachedInputTokens, second.cachedInputTokens).maxOrNull(),
            cacheWriteInputTokens = listOfNotNull(first.cacheWriteInputTokens, second.cacheWriteInputTokens).maxOrNull(),
            outputTokens = listOfNotNull(first.outputTokens, second.outputTokens).maxOrNull(),
            reasoningOutputTokens = listOfNotNull(first.reasoningOutputTokens, second.reasoningOutputTokens).maxOrNull(),
            totalTokens = listOfNotNull(first.totalTokens, second.totalTokens).maxOrNull(),
            contextTokens = listOfNotNull(first.contextTokens, second.contextTokens).maxOrNull(),
            costUsd = listOfNotNull(first.costUsd, second.costUsd).maxOrNull(),
            subagent = first.subagent || second.subagent,
        )
        else -> second
    }

    private fun mergeGemini(first: ProviderTelemetry.Gemini, second: ProviderTelemetry.Gemini): ProviderTelemetry.Gemini =
        first.copy(
            timestampMillis = listOfNotNull(first.timestampMillis, second.timestampMillis).minOrNull(),
            model = second.model ?: first.model,
            effort = second.effort ?: first.effort,
            freshInputTokens = listOfNotNull(first.freshInputTokens, second.freshInputTokens).maxOrNull(),
            inclusiveInputTokens = listOfNotNull(first.inclusiveInputTokens, second.inclusiveInputTokens).maxOrNull(),
            cacheReadTokens = listOfNotNull(first.cacheReadTokens, second.cacheReadTokens).maxOrNull(),
            cacheWriteTokens = listOfNotNull(first.cacheWriteTokens, second.cacheWriteTokens).maxOrNull(),
            outputTokens = listOfNotNull(first.outputTokens, second.outputTokens).maxOrNull(),
            thinkingTokens = listOfNotNull(first.thinkingTokens, second.thinkingTokens).maxOrNull(),
            costUsd = listOfNotNull(first.costUsd, second.costUsd).maxOrNull(),
            truncatedFields = (first.truncatedFields + second.truncatedFields).distinct(),
            subagent = first.subagent || second.subagent,
        )

    private fun mergeAgentUsage(first: AgentUsage, second: AgentUsage): AgentUsage {
        fun maxField(a: Long?, b: Long?): Long? = listOfNotNull(a, b).maxOrNull()
        return AgentUsage(
            inputTokens = maxField(first.inputTokens, second.inputTokens),
            outputTokens = maxField(first.outputTokens, second.outputTokens),
            cachedInputTokens = maxField(first.cachedInputTokens, second.cachedInputTokens),
            cacheWriteInputTokens = maxField(first.cacheWriteInputTokens, second.cacheWriteInputTokens),
            reasoningOutputTokens = maxField(first.reasoningOutputTokens, second.reasoningOutputTokens),
            costUsd = listOfNotNull(first.costUsd, second.costUsd).maxOrNull(),
            durationMillis = maxField(first.durationMillis, second.durationMillis),
            scope = second.scope,
        )
    }

    private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun extractUuids(element: JsonElement?): Set<String> {
        if (element == null) return emptySet()
        return uuidRegex.findAll(element.toString()).map { it.value.lowercase() }.toSet()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
