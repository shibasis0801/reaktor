package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.nio.file.Files
import java.nio.file.Path

/** Immutable, individually atomic records make cursors survive a torn write or owner restart. */
class AgentActivityStore(private val directory: Path) {
    private val cursors = mutableMapOf<String, Long>()
    init { privateDirectory(directory) }

    @Synchronized fun append(runId: String, agent: String, attempt: Int, item: AgentActivityItem): AgentActivityRecord {
        val folder = folder(runId)
        privateDirectory(folder)
        val sequence = (cursors[runId] ?: lastSequence(folder)) + 1
        val bounded = item.copy(input = item.input?.take(6000), output = item.output?.take(16000),
            title = item.title.take(300), subjectRefs = item.subjectRefs.take(100),
            detailTruncated = item.detailTruncated || (item.input?.length ?: 0) > 6000 || (item.output?.length ?: 0) > 16000)
        val record = AgentActivityRecord(sequence, runId, agent, attempt, System.currentTimeMillis(), bounded)
        atomicWrite(folder.resolve("%012d.json".format(sequence)), ConductorJson.encodeToString(AgentActivityRecord.serializer(), record))
        cursors[runId] = sequence
        return record
    }

    @Synchronized fun page(runId: String, after: Long = 0, limit: Int = 50): AgentActivityPage {
        require(after >= 0 && limit in 1..100)
        val folder = folder(runId)
        val last = cursors[runId] ?: lastSequence(folder)
        val records = mutableListOf<AgentActivityRecord>()
        var bytes = 0L
        for (sequence in (after + 1)..minOf(last, after + limit)) {
            val path = folder.resolve("%012d.json".format(sequence))
            bytes += Files.size(path)
            if (bytes > 500000 && records.isNotEmpty()) break
            records += ConductorJson.decodeFromString(AgentActivityRecord.serializer(), Files.readString(path))
        }
        val next = records.lastOrNull()?.sequence ?: after
        return AgentActivityPage(records, next, next < last)
    }

    fun unresolved(runId: String, attempt: Int): List<AgentActivityRecord> {
        val active = mutableMapOf<String, AgentActivityRecord>()
        var cursor = 0L
        do {
            val page = page(runId, cursor, 100)
            page.records.filter { it.attempt == attempt && it.item.kind == ActivityKind.Tool }.forEach {
                val key = "${it.agent}:${it.item.id}"
                if (it.item.status == ActivityStatus.Started) active[key] = it
                else if (it.item.status in listOf(ActivityStatus.Completed, ActivityStatus.Failed, ActivityStatus.Interrupted)) active.remove(key)
            }
            cursor = page.nextCursor
        } while (page.hasMore)
        return active.values.toList()
    }

    private fun folder(runId: String): Path {
        require(runId.matches(Regex("[a-f0-9]{64}")))
        return directory.resolve(runId)
    }
    private fun lastSequence(folder: Path): Long = if (!Files.exists(folder)) 0 else Files.list(folder).use { files ->
        files.map { it.fileName.toString().removeSuffix(".json").toLongOrNull() ?: 0L }.max(Long::compareTo).orElse(0L)
    }
}
