package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.telemetry.ClaudeTelemetry
import dev.shibasis.reaktor.conductor.telemetry.CodexTelemetry
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object WorkLogs {
    private const val WindowBytes = 8L shl 20
    private const val WholeBytes = 96L shl 20
    private const val Kept = 96
    private const val LimitBytes = 1L shl 20
    private const val NewLine = '\n'.code.toByte()

    private class Scan(val start: Long, val parser: LogParser) {
        var offset = start
        var length = -1L
        var modified = -1L
        var log = WorkLog.Empty
        val events = mutableListOf<Pair<Long, AgentEvent>>()
        var earlierPromptNotShown = false
        var promptOffset = 0L
        var discardedThrough = 0L
        var lastUsed = 0L
        var dirty = true
        var paths = emptySet<String>()
        var samples = emptyList<dev.shibasis.reaktor.conductor.telemetry.ProviderTelemetry.Gemini>()
    }

    private class Agents(val modified: Long, val files: Map<String, File>)

    private val scans = ConcurrentHashMap<String, Scan>()
    private val agents = ConcurrentHashMap<String, Agents>()
    private val followers = ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>()
    private val sequence = AtomicLong()

    private fun prune(key: String, scan: Scan) {
        val through = followers[key]?.values?.minOrNull() ?: Long.MAX_VALUE
        var end = 0
        while (end < scan.events.size && scan.events[end].first <= through) end++
        if (end > 0) {
            scan.discardedThrough = maxOf(scan.discardedThrough, scan.events[end - 1].first)
            scan.events.subList(0, end).clear()
        }
    }

    internal fun hasGeminiFollower(file: File): Boolean {
        val prefix = "${RuntimeKind.Gemini}\u0000${file.canonicalPath}\u0000"
        return followers.any { (key, values) -> key.startsWith(prefix) && values.isNotEmpty() }
    }

    internal class GeminiFollower internal constructor(private val file: File, private val root: String?, private val from: Long) : AutoCloseable {
        private val key = scanKey(RuntimeKind.Gemini, file, root, false)
        private val id = sequence.incrementAndGet()
        private var closed = false
        init {
            synchronized(followers) { followers.computeIfAbsent(key) { ConcurrentHashMap() }[id] = from }
            scans[key]?.let { scan -> synchronized(scan) { if (from < scan.discardedThrough) scans.remove(key, scan) } }
        }
        @Synchronized fun read(): GeminiSnapshot {
            check(!closed) { "The transcript follower is closed" }
            val scan = update(RuntimeKind.Gemini, file, root, materialize = true)
                ?: return GeminiSnapshot(WorkLog.Empty, from, emptyList(), emptySet(), emptyList())
            return synchronized(scan) {
                val offset = followers[key]?.get(id) ?: from
                var low = 0
                var high = scan.events.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (scan.events[middle].first <= offset) low = middle + 1 else high = middle
                }
                val events = scan.events.subList(low, scan.events.size).map { it.second }
                followers[key]?.set(id, scan.offset)
                prune(key, scan)
                GeminiSnapshot(scan.log, scan.offset, events, scan.paths, scan.samples)
            }
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            synchronized(followers) { followers[key]?.let { values -> values.remove(id); if (values.isEmpty()) followers.remove(key, values) } }
            scans[key]?.let { scan -> synchronized(scan) { prune(key, scan) } }
        }
    }

    internal fun followGemini(file: File, root: String?, from: Long = 0) = GeminiFollower(file, root, from)

    private fun scanKey(runtime: RuntimeKind, file: File, root: String?, whole: Boolean) =
        "$runtime\u0000${file.canonicalPath}\u0000${root?.let { File(it).canonicalPath }}\u0000$whole"

    fun transcript(runtime: RuntimeKind, session: String, home: File = File(System.getProperty("user.home")), now: Instant = Instant.now()): File? = when (runtime) {
        RuntimeKind.ClaudeCode -> File(home, ".claude/projects").listFiles().orEmpty().asSequence().filter(File::isDirectory)
            .map { File(it, "$session.jsonl") }.firstOrNull(File::isFile)
        RuntimeKind.Codex -> codex(home, session, now)
        RuntimeKind.Gemini -> listOf(".gemini/antigravity/brain", ".gemini/antigravity-cli/brain").asSequence()
            .map { File(home, "$it/$session/.system_generated/logs/transcript.jsonl") }.firstOrNull(File::isFile)
        else -> null
    }

    fun read(runtime: RuntimeKind, file: File, root: String?, whole: Boolean = false): WorkLog =
        update(runtime, file, root, whole, materialize = true)?.log ?: WorkLog.Empty

    private fun update(runtime: RuntimeKind, file: File, root: String?, whole: Boolean = false, materialize: Boolean = false): Scan? {
        if (!file.isFile) return null
        val key = scanKey(runtime, file, root, whole)
        val length = file.length()
        val known = scans[key]?.takeIf { it.offset <= length }
        val scan = known ?: run {
            val start = alignedStart(file, maxOf(0L, length - if (whole) WholeBytes else WindowBytes))
            val reader = parser(runtime, file, root)
            if (reader is ClaudeLog && start > 0) linesAt(file, 0, false, minOf(start, 256L * 1024)) { _, record -> reader.header(record) }
            val previous = if (start > 0) reverseRecord(file, start, when (runtime) {
                RuntimeKind.Codex -> "UserMessage"
                RuntimeKind.Gemini -> "USER_INPUT"
                else -> "user"
            }) { record ->
                val candidate = parser(runtime, file, root)
                candidate.line(record)
                candidate.log(false).turns.any { it.prompt != null }
            } else null
            previous?.record?.let(reader::line)
            Scan(start, reader).also {
                it.earlierPromptNotShown = previous?.capped == true
                synchronized(scans) {
                    if (scans.size >= Kept) scans.entries.filter { entry -> followers[entry.key].isNullOrEmpty() }
                        .minByOrNull { entry -> entry.value.lastUsed }?.let { entry -> scans.remove(entry.key, entry.value) }
                    scans[key] = it
                }
            }
        }
        return synchronized(scan) {
            scan.lastUsed = sequence.incrementAndGet()
            val modified = file.lastModified()
            if (scan.length != length || scan.modified != modified) {
                scan.offset = linesAt(file, scan.offset, skipFirst = false) { end, record ->
                    scan.dirty = true
                    val parser = scan.parser
                    when (parser) {
                        is AntigravityLog -> {
                            val events = parser.events(record)
                            if (!followers[key].isNullOrEmpty()) events.forEach { scan.events += end to it }
                            else scan.discardedThrough = end
                        }
                        is ClaudeLog -> {
                            parser.recordOffset(end)
                            val prompts = parser.prompts
                            parser.events(record).forEach { scan.events += end to it }
                            if (parser.prompts != prompts) scan.promptOffset = end
                        }
                        else -> parser.line(record)
                    }
                }
                scan.length = length
                scan.modified = modified
            }
            if (materialize && scan.dirty) {
                scan.log = scan.parser.log(earlier = scan.start > 0).let { log ->
                    log.copy(earlierPromptNotShown = scan.earlierPromptNotShown && log.turns.firstOrNull()?.prompt == null)
                }
                (scan.parser as? AntigravityLog)?.let { parser -> scan.paths = parser.paths.toSet(); scan.samples = parser.samples }
                scan.dirty = false
            }
            scan
        }
    }

    internal data class GeminiSnapshot(val log: WorkLog, val offset: Long, val events: List<AgentEvent>, val paths: Set<String>,
        val samples: List<dev.shibasis.reaktor.conductor.telemetry.ProviderTelemetry.Gemini>)

    internal fun gemini(file: File, root: String?, from: Long = Long.MAX_VALUE): GeminiSnapshot {
        if (from != Long.MAX_VALUE) return followGemini(file, root, from).use { it.read() }
        val scan = update(RuntimeKind.Gemini, file, root, materialize = true)
            ?: return GeminiSnapshot(WorkLog.Empty, 0, emptyList(), emptySet(), emptyList())
        return synchronized(scan) { GeminiSnapshot(scan.log, scan.offset, emptyList(), scan.paths, scan.samples) }
    }

    internal data class ClaudeSnapshot(val offset: Long, val promptOffset: Long, val events: List<AgentEvent>,
        val answer: String, val ended: Boolean, val usage: dev.shibasis.reaktor.conductor.AgentUsage)

    internal fun <T> claude(file: File, root: String?, read: (ClaudeLog) -> T): T? {
        val scan = update(RuntimeKind.ClaudeCode, file, root) ?: return null
        return synchronized(scan) { read(scan.parser as ClaudeLog) }
    }

    internal fun claudeTurn(file: File, root: String?, from: Long = Long.MAX_VALUE): ClaudeSnapshot? {
        val scan = update(RuntimeKind.ClaudeCode, file, root) ?: return null
        return synchronized(scan) {
            val reader = scan.parser as ClaudeLog
            ClaudeSnapshot(scan.offset, scan.promptOffset, scan.events.asSequence().filter { it.first > from }.map { it.second }.toList(),
                reader.answer, reader.ended, reader.usage)
        }
    }

    internal fun codexStatus(file: File): CodexTurnBoundary? = CodexStatus.read(file)

    fun limits(file: File): RateLimits? {
        if (!file.isFile) return null
        val start = maxOf(0L, file.length() - LimitBytes)
        var found: RateLimits? = null
        lines(file, start, skipFirst = start > 0) { record ->
            (CodexTelemetry.parseRateLimits(record) ?: ClaudeTelemetry.parseRateLimits(record))?.let { found = it }
        }
        return found
    }

    private fun parser(runtime: RuntimeKind, file: File, root: String?): LogParser = when (runtime) {
        RuntimeKind.Codex -> CodexLog(root)
        RuntimeKind.Gemini -> AntigravityLog(root)
        else -> ClaudeLog(root) { call -> subagent(file, call, root) }
    }

    private fun subagent(file: File, call: String, root: String?): WorkLog? {
        val folder = File(file.parentFile, "${file.nameWithoutExtension}/subagents")
        if (!folder.isDirectory) return null
        val modified = folder.lastModified()
        val index = agents[folder.path]?.takeIf { it.modified == modified && call in it.files } ?: Agents(modified,
            folder.listFiles { candidate -> candidate.name.endsWith(".meta.json") }.orEmpty().mapNotNull { meta ->
                runCatching { parseRecord(meta.readText())?.str("toolUseId") }.getOrNull()?.let { id -> id to File(folder, meta.name.removeSuffix(".meta.json") + ".jsonl") }
            }.toMap()).also { agents[folder.path] = it }
        return index.files[call]?.takeIf(File::isFile)?.let { read(RuntimeKind.ClaudeCode, it, root) }
    }

    private fun codex(home: File, session: String, now: Instant): File? {
        val today = LocalDate.ofInstant(now, ZoneId.systemDefault())
        return (0L..30L).asSequence().map { today.minusDays(it) }
            .flatMap { day -> File(home, ".codex/sessions/%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth)).listFiles().orEmpty().asSequence() }
            .firstOrNull { it.name.endsWith("-$session.jsonl") }
    }

    private fun alignedStart(file: File, from: Long): Long = RandomAccessFile(file, "r").use { input ->
        if (from == 0L) return@use 0L
        input.seek(from - 1)
        if (input.read() == NewLine.toInt()) return@use from
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val begin = input.filePointer
            val count = input.read(buffer)
            if (count <= 0) return@use input.length()
            for (index in 0 until count) if (buffer[index] == NewLine) return@use begin + index + 1
        }
        error("Unreachable")
    }

    internal data class ReverseRead(val record: JsonObject?, val bytes: Long, val lines: Int, val capped: Boolean)

    internal fun previousRecord(file: File, before: Long, marker: String, accept: (JsonObject) -> Boolean): JsonObject? =
        reverseRecord(file, before, marker, accept).record

    internal fun reverseRecord(file: File, before: Long, marker: String, accept: (JsonObject) -> Boolean): ReverseRead =
        RandomAccessFile(file, "r").use { input ->
            val budget = 16L shl 20
            val needle = marker.toByteArray(Charsets.UTF_8).reversedArray()
            var position = minOf(before, input.length())
            var read = 0L
            var lines = 0
            val buffer = ByteArray(1 shl 16)
            val line = ByteArray(1 shl 20)
            var size = 0
            var overflow = false
            var first = true
            fun record(): JsonObject? {
                lines++
                if (overflow || size < needle.size) return null
                val matches = (0..size - needle.size).any { offset -> needle.indices.all { line[offset + it] == needle[it] } }
                if (!matches) return null
                val bytes = ByteArray(size) { line[size - it - 1] }
                return parseRecord(bytes.toString(Charsets.UTF_8))?.takeIf(accept)
            }
            while (position > 0 && read < budget && lines < 10_000) {
                val count = minOf(buffer.size.toLong(), position, budget - read).toInt()
                val begin = position - count
                input.seek(begin)
                input.readFully(buffer, 0, count)
                read += count
                for (index in count - 1 downTo 0) {
                    val byte = buffer[index]
                    if (first && byte == NewLine) { first = false; continue }
                    first = false
                    if (byte == NewLine) {
                        record()?.let { return@use ReverseRead(it, read, lines, false) }
                        if (lines == 10_000) return@use ReverseRead(null, read, lines, begin + index > 0)
                        size = 0
                        overflow = false
                    } else if (size < line.size) line[size++] = byte else overflow = true
                }
                position = begin
            }
            if (position == 0L && (size > 0 || overflow)) {
                record()?.let { return@use ReverseRead(it, read, lines, false) }
            }
            ReverseRead(null, read, lines, position > 0)
        }

    internal fun lines(file: File, from: Long, skipFirst: Boolean, each: (JsonObject) -> Unit): Long =
        linesAt(file, from, skipFirst) { _, record -> each(record) }

    private fun linesAt(file: File, from: Long, skipFirst: Boolean, through: Long = Long.MAX_VALUE, each: (Long, JsonObject) -> Unit): Long = RandomAccessFile(file, "r").use { raf ->
        val length = minOf(raf.length(), through)
        raf.seek(from)
        val buffer = ByteArray(1 shl 16)
        val line = ByteArrayOutputStream()
        var position = from
        var consumed = from
        var skipping = skipFirst
        while (position < length) {
            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), length - position).toInt())
            if (read <= 0) break
            var begin = 0
            for (index in 0 until read) {
                if (buffer[index] != NewLine) continue
                if (!skipping) {
                    line.write(buffer, begin, index - begin)
                    parseRecord(line.toString(Charsets.UTF_8))?.let { each(position + index + 1, it) }
                }
                line.reset()
                skipping = false
                begin = index + 1
                consumed = position + index + 1
            }
            if (!skipping && begin < read) line.write(buffer, begin, read - begin)
            position += read
        }
        consumed
    }
}
