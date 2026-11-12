package dev.shibasis.reaktor.conductor.telemetry

import dev.shibasis.reaktor.cloud.cloudflare.AiAllowanceWindow
import dev.shibasis.reaktor.cloud.cloudflare.ClaudeModelStats
import dev.shibasis.reaktor.cloud.cloudflare.LocalClaudeTelemetry
import dev.shibasis.reaktor.cloud.cloudflare.LocalCodexTelemetry
import dev.shibasis.reaktor.cloud.cloudflare.LocalGeminiTelemetry
import dev.shibasis.reaktor.cloud.cloudflare.LocalHarnessTelemetryProvider
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

class ConductorLocalHarnessTelemetryProvider : LocalHarnessTelemetryProvider {
    override fun readCodex(codexDir: File, days: Int, now: Long): LocalCodexTelemetry {
        if (!File(codexDir, "sessions").isDirectory && !File(codexDir, "archived_sessions").isDirectory) {
            return LocalCodexTelemetry()
        }
        val home = codexDir.parentFile ?: codexDir
        val cutoff = now - days * 86_400_000L
        val until = maxOf(now + 86_400_000L, cutoff + 1L)
        val scan = HarnessTelemetryScanner.scan(
            home = home,
            since = cutoff,
            until = until,
            runtimes = setOf(RuntimeKind.Codex),
            onlyModifiedSince = cutoff,
        )
        val rollup = scan.requests.map { it.sample }.rollupTelemetry()
        val latestRated = scan.requests.asReversed().mapNotNull { it.provider as? ProviderTelemetry.Codex }
            .firstOrNull { it.rateLimits != null || it.creditBalance != null }
        val rateLimits = latestRated?.rateLimits
        val primaryRaw = rateLimits?.windows?.firstOrNull { (it.minutes ?: 300L) < 1440L }
        val secondaryRaw = rateLimits?.windows?.firstOrNull { (it.minutes ?: 0L) >= 1440L }
            ?: rateLimits?.windows?.getOrNull(1)
        val latestPrimary = primaryRaw?.let { win ->
            val mins = win.minutes ?: 300L
            AiAllowanceWindow(
                label = "Codex ${mins / 60}h Allowance Window",
                usedPercent = win.usedPercent,
                remaining = "${"%.0f".format((100.0 - win.usedPercent).coerceAtLeast(0.0))}% available",
                limit = "${mins / 60}h rolling window",
                resetsAtMillis = win.resetsAt,
            )
        }
        val latestSecondary = secondaryRaw?.let { win ->
            val mins = win.minutes ?: 10_080L
            AiAllowanceWindow(
                label = "Codex ${mins / 1440}d Weekly Allowance",
                usedPercent = win.usedPercent,
                remaining = "${"%.0f".format((100.0 - win.usedPercent).coerceAtLeast(0.0))}% available",
                limit = "${mins / 1440}d rolling window",
                resetsAtMillis = win.resetsAt,
            )
        }
        val inputTokens = rollup.inputTokens ?: 0L
        val cachedTokens = rollup.cachedInputTokens ?: 0L
        val outputTokens = rollup.outputTokens ?: 0L
        val fallbackUsd = ((inputTokens - cachedTokens).coerceAtLeast(0L) / 1_000_000.0 * 2.50) +
            (cachedTokens / 1_000_000.0 * 0.625) +
            (outputTokens / 1_000_000.0 * 10.0)
        val resolvedCost = rollup.costUsd ?: rollup.estimatedCostUsd ?: fallbackUsd

        return LocalCodexTelemetry(
            sessionCount7d = scan.requests.map { it.sessionId }.distinct().size,
            requests7d = rollup.requests.toLong(),
            inputTokens7d = inputTokens,
            cachedTokens7d = cachedTokens,
            outputTokens7d = outputTokens,
            reasoningTokens7d = rollup.reasoningOutputTokens ?: 0L,
            cost7dUsd = resolvedCost,
            compactions7d = rollup.compactions,
            primaryWindow = latestPrimary,
            secondaryWindow = latestSecondary,
            creditBalance = latestRated?.creditBalance?.let { "%.2f".format(it) },
            planType = rateLimits?.plan,
        )
    }

    override fun readClaude(claudeDir: File, days: Int, now: Long): LocalClaudeTelemetry {
        if (!File(claudeDir, "projects").isDirectory) return LocalClaudeTelemetry()
        val home = claudeDir.parentFile ?: claudeDir
        val cutoff = now - days * 86_400_000L
        val until = maxOf(now + 86_400_000L, cutoff + 1L)
        val scan = HarnessTelemetryScanner.scan(
            home = home,
            since = cutoff,
            until = until,
            runtimes = setOf(RuntimeKind.ClaudeCode),
            onlyModifiedSince = cutoff,
        )
        val rollup = scan.requests.map { it.sample }.rollupTelemetry()
        val byModel = linkedMapOf<String, ClaudeModelStats>()
        for (req in scan.requests) {
            val sample = req.sample
            if (!sample.hasTokens && sample.costUsd == null) continue
            val model = req.model?.takeIf { !it.startsWith("<") } ?: "claude-opus-5-5"
            val stat = byModel.getOrPut(model) { ClaudeModelStats() }
            stat.requests++
            stat.inputTokens += sample.inputTokens ?: 0L
            stat.cacheReadTokens += sample.cachedInputTokens ?: 0L
            stat.cacheCreationTokens += sample.cacheWriteInputTokens ?: 0L
            stat.outputTokens += sample.outputTokens ?: 0L
            stat.costUsd += sample.costUsd ?: sample.estimatedCostUsd ?: 0.0
        }
        val inclusiveInput = rollup.inputTokens ?: 0L
        val cacheRead = rollup.cachedInputTokens ?: 0L
        val cacheCreate = rollup.cacheWriteInputTokens ?: 0L
        val outputTokens = rollup.outputTokens ?: 0L
        val freshInput = rollup.freshInputTokens ?: (inclusiveInput - cacheRead - cacheCreate).coerceAtLeast(0L)
        val fallbackUsd = (freshInput / 1_000_000.0 * 5.0) +
            (cacheRead / 1_000_000.0 * 0.50) +
            (cacheCreate / 1_000_000.0 * 6.25) +
            (outputTokens / 1_000_000.0 * 25.0)
        val resolvedCost = rollup.costUsd ?: rollup.estimatedCostUsd ?: fallbackUsd

        return LocalClaudeTelemetry(
            sessionCount7d = scan.requests.map { it.sessionId }.distinct().size,
            requests7d = rollup.requests.toLong(),
            inputTokens7d = inclusiveInput,
            cacheReadTokens7d = cacheRead,
            cacheCreationTokens7d = cacheCreate,
            outputTokens7d = outputTokens,
            thinkingTokens7d = rollup.reasoningOutputTokens ?: 0L,
            cost7dUsd = resolvedCost,
            compactions7d = rollup.compactions,
            byModel = byModel,
        )
    }

    override fun readGemini(geminiDir: File, days: Int, now: Long): LocalGeminiTelemetry {
        val brainDirs = listOf(File(geminiDir, "antigravity/brain"), File(geminiDir, "antigravity-cli/brain")).filter(File::isDirectory)
        if (brainDirs.isEmpty()) return LocalGeminiTelemetry()
        val home = geminiDir.parentFile ?: geminiDir
        val cutoff = now - days * 86_400_000L
        val until = maxOf(now + 86_400_000L, cutoff + 1L)
        val scan = HarnessTelemetryScanner.scan(
            home = home,
            since = cutoff,
            until = until,
            runtimes = setOf(RuntimeKind.Gemini),
            onlyModifiedSince = cutoff,
        )
        val rollup = scan.requests.map { it.sample }.rollupTelemetry()
        val scannedConversations = scan.allRequests.flatMap { req ->
            listOf(req.sessionId.substringBefore(':'), req.sessionId.substringAfter(':', "")).filter(String::isNotEmpty)
        }.toSet()
        val rootConversations = scan.requests
            .filter { !it.sample.compacted }
            .map { it.sessionId.substringBefore(':') }
            .toMutableSet()
        var untokenizedSteps = 0L
        for (brainRoot in brainDirs) {
            for (folder in brainRoot.listFiles().orEmpty()) {
                val folderId = folder.name.lowercase()
                if (!folder.isDirectory || folderId in scannedConversations) continue
                val transcript = File(folder, ".system_generated/logs/transcript.jsonl")
                if (!transcript.isFile || transcript.lastModified() < cutoff) continue
                var activeInWindow = false
                runCatching {
                    transcript.useLines { lines ->
                        lines.forEach { line ->
                            if (!line.contains("\"PLANNER_RESPONSE\"")) return@forEach
                            val obj = runCatching { ConductorJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
                            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "PLANNER_RESPONSE") return@forEach
                            val rawAt = (obj["created_at"] as? JsonPrimitive)?.contentOrNull
                                ?: (obj["timestamp"] as? JsonPrimitive)?.contentOrNull
                            val at = parseIsoEpochMillis(rawAt) ?: transcript.lastModified()
                            if (at in cutoff until until) {
                                activeInWindow = true
                                untokenizedSteps++
                            }
                        }
                    }
                }
                if (activeInWindow) rootConversations += folderId
            }
        }
        val inclusiveInput = rollup.inputTokens ?: 0L
        val cachedInput = rollup.cachedInputTokens ?: 0L
        val outputTokens = rollup.outputTokens ?: 0L
        val fallbackUsd = ((inclusiveInput - cachedInput).coerceAtLeast(0L) / 1_000_000.0 * 1.25) +
            (cachedInput / 1_000_000.0 * 0.31) +
            (outputTokens / 1_000_000.0 * 10.0)
        val resolvedCost = rollup.costUsd ?: rollup.estimatedCostUsd ?: fallbackUsd

        return LocalGeminiTelemetry(
            conversationCount7d = rootConversations.size,
            requests7d = rollup.requests.toLong() + rollup.unknownUsageRequests.toLong() + untokenizedSteps,
            inputTokens7d = inclusiveInput,
            cachedTokens7d = cachedInput,
            outputTokens7d = outputTokens,
            thinkingTokens7d = rollup.reasoningOutputTokens ?: 0L,
            cost7dUsd = resolvedCost,
            compactions7d = rollup.compactions,
        )
    }
}
