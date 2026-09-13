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
            nativeAgents = item.nativeAgents.take(50).map { it.copy(id = it.id.take(200), parentId = it.parentId?.take(200), name = it.name?.take(200), output = it.output?.take(6000)) },
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
            val text = if (Files.exists(path)) Files.readString(path) else java.util.zip.ZipFile(folder.resolve("archive.zip").toFile()).use { zip ->
                zip.getInputStream(requireNotNull(zip.getEntry(path.fileName.toString()))).bufferedReader().use { it.readText() }
            }
            bytes += text.toByteArray().size
            if (bytes > 500000 && records.isNotEmpty()) break
            records += ConductorJson.decodeFromString(AgentActivityRecord.serializer(), text)
        }
        val next = records.lastOrNull()?.sequence ?: after
        return AgentActivityPage(records, next, next < last)
    }

    /** Terminal runs only, enforced by the owner. Detail remains readable through the same cursors. */
    @Synchronized fun archive(runId: String): Long {
        val folder = folder(runId)
        if (!Files.exists(folder)) return 0
        val files = Files.list(folder).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[0-9]{12}\\.json")) }.sorted().toList() }
        if (files.isEmpty()) return 0
        val archive = folder.resolve("archive.zip")
        val temporary = Files.createTempFile(folder, ".archive-", ".zip")
        try {
            val last = lastSequence(folder)
            java.util.zip.ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                val replacements = files.map { it.fileName.toString() }.toSet()
                if (Files.exists(archive)) java.util.zip.ZipFile(archive.toFile()).use { old -> old.entries().asSequence().filter { it.name !in replacements }.forEach { entry ->
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name)); old.getInputStream(entry).use { it.copyTo(zip) }; zip.closeEntry()
                } }
                files.forEach { file -> zip.putNextEntry(java.util.zip.ZipEntry(file.fileName.toString())); Files.copy(file, zip); zip.closeEntry() }
            }
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            java.util.zip.ZipFile(temporary.toFile()).use { verified -> files.forEach { file ->
                val saved = verified.getInputStream(verified.getEntry(file.fileName.toString())).use { it.readBytes() }
                check(saved.contentEquals(Files.readAllBytes(file)))
            } }
            val before = files.sumOf { Files.size(it) } + if (Files.exists(archive)) Files.size(archive) else 0
            Files.move(temporary, archive, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            atomicWrite(folder.resolve("last-sequence"), last.toString())
            files.forEach(Files::delete)
            return before - Files.size(archive)
        } finally { Files.deleteIfExists(temporary) }
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
        maxOf(files.map { it.fileName.toString().removeSuffix(".json").toLongOrNull() ?: 0L }.max(Long::compareTo).orElse(0L),
            folder.resolve("last-sequence").takeIf(Files::exists)?.let { Files.readString(it).toLong() } ?: 0L)
    }
}
