package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessSession
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
) {
    companion object {
        fun of(session: HarnessSession, profile: String? = null, thread: String? = null) = LedgerRecord(
            id = "${session.runtime.name.lowercase()}:${session.id}",
            harness = session.runtime,
            model = session.model,
            effort = session.effort,
            profile = profile,
            thread = thread,
            startedAt = session.startedAt,
            endedAt = session.lastActiveAt,
            tokens = session.tokens,
            steps = session.steps.size,
            doorCalls = session.doorCalls,
            files = session.files.size,
            checks = session.checklist.size,
            done = session.checklist.count { it.state == PlanState.Done },
        )
    }
}

data class LedgerStanding(val harness: RuntimeKind, val model: String, val effort: String?, val sessions: Int, val tokens: Long, val checks: Int, val done: Int) {
    val tokensPerSession: Long get() = if (sessions == 0) 0 else tokens / sessions
    val finished: Double? get() = if (checks == 0) null else done.toDouble() / checks
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
                .map { (key, rows) -> LedgerStanding(key.first, key.second, key.third, rows.size, rows.sumOf { it.tokens ?: 0L }, rows.sumOf { it.checks }, rows.sumOf { it.done }) }
                .sortedWith(compareByDescending<LedgerStanding> { it.sessions }.thenBy { it.model }.thenBy { it.effort.orEmpty() })
    }
}
