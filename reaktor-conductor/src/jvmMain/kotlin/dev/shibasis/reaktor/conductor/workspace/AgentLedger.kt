package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.conductor.cli.SessionTotals
import dev.shibasis.reaktor.conductor.worklog.PlanState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class LedgerRecord(
    val id: String,
    val harness: RuntimeKind,
    val model: String? = null,
    val effort: String? = null,
    val profile: String? = null,
    val thread: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long,
    val tokens: Long? = null,
    val steps: Int = 0,
    val doorCalls: Int = 0,
    val files: Int = 0,
    val checks: Int = 0,
    val done: Int = 0,
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val peakContextTokens: Long? = null,
    val costUsd: Double? = null,
    val estimatedCostUsd: Double? = null,
) {
    companion object {
        fun of(session: HarnessSession, profile: String? = null, thread: String? = null, totals: SessionTotals? = null) = LedgerRecord(
            id = "${session.runtime.name.lowercase()}:${session.id}",
            harness = session.runtime,
            model = session.model,
            effort = session.effort,
            profile = profile,
            thread = thread,
            startedAt = session.startedAt,
            endedAt = session.lastActiveAt,
            tokens = session.tokens,
            steps = totals?.steps ?: session.steps.size,
            doorCalls = totals?.doorCalls ?: session.doorCalls,
            files = session.files.size,
            checks = session.checklist.size,
            done = session.checklist.count { it.state == PlanState.Done },
            inputTokens = session.telemetry?.inputTokens,
            cachedInputTokens = session.telemetry?.cachedInputTokens,
            cacheWriteInputTokens = session.telemetry?.cacheWriteInputTokens,
            outputTokens = session.telemetry?.outputTokens,
            reasoningTokens = session.telemetry?.reasoningOutputTokens,
            peakContextTokens = session.telemetry?.peakContextTokens,
            costUsd = session.telemetry?.costUsd,
            estimatedCostUsd = session.telemetry?.estimatedCostUsd,
        )
    }
}

data class LedgerStanding(
    val harness: RuntimeKind,
    val model: String,
    val effort: String?,
    val sessions: Int,
    val tokens: Long,
    val checks: Int,
    val done: Int,
    val steps: Int = 0,
    val doorCalls: Int = 0,
    val cachedShare: Double? = null,
    val meanEffectiveCostUsd: Double? = null,
    val usesEstimatedCost: Boolean = false,
) {
    val tokensPerSession: Long get() = if (sessions == 0) 0 else tokens / sessions
    val finished: Double? get() = if (checks == 0) null else done.toDouble() / checks
    val doorRatio: Double? get() = if (steps == 0) null else doorCalls.toDouble() / steps.toDouble()
}

class AgentLedger(private val directory: Path) {
    @Synchronized
    fun record(records: Collection<LedgerRecord>): Int {
        if (records.isEmpty()) return 0
        val known = read().associateBy { it.id }
        val fresh = records.distinctBy { it.id }.filter { known[it.id] != it }
        if (fresh.isEmpty()) return 0
        Files.createDirectories(directory)
        fresh.groupBy { day(it.endedAt) }.forEach { (day, rows) ->
            Files.writeString(directory.resolve("$day.jsonl"), rows.joinToString("") { Format.encodeToString(LedgerRecord.serializer(), it) + "\n" },
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        return fresh.size
    }

    fun read(): List<LedgerRecord> {
        val days = directory.toFile().listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }?.sortedBy(File::getName).orEmpty()
        val latest = LinkedHashMap<String, LedgerRecord>()
        days.forEach { file ->
            file.useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    runCatching { Format.decodeFromString(LedgerRecord.serializer(), line) }.getOrNull()?.let { latest[it.id] = it }
                }
            }
        }
        return latest.values.sortedByDescending { it.endedAt }
    }

    private fun day(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString()

    companion object {
        private val Format = Json { ignoreUnknownKeys = true }

        fun directory(root: File): Path = AgentWorkspaceConnection.defaultDirectory(root).resolve("ledger")

        fun standings(records: Collection<LedgerRecord>): List<LedgerStanding> =
            records.mapNotNull { record -> record.model?.let { Triple(record.harness, it, record.effort) to record } }
                .groupBy({ it.first }, { it.second })
                .map { (key, rows) ->
                    val cachedShares = rows.mapNotNull { row ->
                        val inp = row.inputTokens?.takeIf { it > 0L } ?: return@mapNotNull null
                        val cached = row.cachedInputTokens ?: return@mapNotNull null
                        (cached.toDouble() / inp.toDouble()).coerceIn(0.0, 1.0)
                    }
                    val costs = rows.mapNotNull { it.costUsd ?: it.estimatedCostUsd }
                    val estimated = rows.any { it.costUsd == null && it.estimatedCostUsd != null }
                    LedgerStanding(
                        harness = key.first,
                        model = key.second,
                        effort = key.third,
                        sessions = rows.size,
                        tokens = rows.sumOf { it.tokens ?: ((it.inputTokens ?: 0L) + (it.outputTokens ?: 0L)) },
                        checks = rows.sumOf { it.checks },
                        done = rows.sumOf { it.done },
                        steps = rows.sumOf { it.steps },
                        doorCalls = rows.sumOf { it.doorCalls },
                        cachedShare = cachedShares.takeIf { it.isNotEmpty() }?.average(),
                        meanEffectiveCostUsd = costs.takeIf { it.isNotEmpty() }?.average(),
                        usesEstimatedCost = estimated,
                    )
                }
                .sortedWith(compareByDescending<LedgerStanding> { it.sessions }.thenBy { it.model }.thenBy { it.effort.orEmpty() })
    }
}
