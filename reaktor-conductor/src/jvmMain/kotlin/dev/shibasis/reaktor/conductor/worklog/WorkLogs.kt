package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
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
    }

    private class Agents(val modified: Long, val files: Map<String, File>)

    private val scans = ConcurrentHashMap<String, Scan>()
    private val agents = ConcurrentHashMap<String, Agents>()

    fun transcript(runtime: RuntimeKind, session: String, home: File = File(System.getProperty("user.home")), now: Instant = Instant.now()): File? = when (runtime) {
        RuntimeKind.ClaudeCode -> File(home, ".claude/projects").listFiles().orEmpty().asSequence().filter(File::isDirectory)
            .map { File(it, "$session.jsonl") }.firstOrNull(File::isFile)
        RuntimeKind.Codex -> codex(home, session, now)
        RuntimeKind.Gemini -> listOf(".gemini/antigravity/brain", ".gemini/antigravity-cli/brain").asSequence()
            .map { File(home, "$it/$session/.system_generated/logs/transcript.jsonl") }.firstOrNull(File::isFile)
        else -> null
    }

    fun read(runtime: RuntimeKind, file: File, root: String?, whole: Boolean = false): WorkLog {
        if (!file.isFile) return WorkLog.Empty
        val key = "$runtime\u0000${file.path}\u0000$root\u0000$whole"
        val length = file.length()
        val known = scans[key]?.takeIf { it.offset <= length }
        val scan = known ?: run {
            val start = alignedStart(file, maxOf(0L, length - if (whole) WholeBytes else WindowBytes))
            val reader = parser(runtime, file, root)
            if (start > 0) previousRecord(file, start, when (runtime) {
                RuntimeKind.Codex -> "UserMessage"
                RuntimeKind.Gemini -> "USER_INPUT"
                else -> "user"
            }) { record ->
                val candidate = parser(runtime, file, root)
                candidate.line(record)
                candidate.log(false).turns.any { it.prompt != null }
            }?.let(reader::line)
            Scan(start, reader).also {
                if (scans.size >= Kept) scans.clear()
                scans[key] = it
            }
        }
        return synchronized(scan) {
            val modified = file.lastModified()
            if (scan.length != length || scan.modified != modified) {
                scan.offset = lines(file, scan.offset, skipFirst = false) { scan.parser.line(it) }
                scan.length = length
                scan.modified = modified
            }
            scan.parser.log(earlier = scan.start > 0).also { scan.log = it }
        }
    }

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

    internal fun previousRecord(file: File, before: Long, marker: String, accept: (JsonObject) -> Boolean): JsonObject? =
        RandomAccessFile(file, "r").use { input ->
            var end = minOf(before, input.length())
            if (end == 0L) return@use null
            input.seek(end - 1)
            if (input.read() == NewLine.toInt()) end--
            var position = end
            val buffer = ByteArray(1 shl 16)
            fun record(start: Long, end: Long): JsonObject? {
                if (end <= start || end - start > Int.MAX_VALUE) return null
                val bytes = ByteArray((end - start).toInt())
                input.seek(start)
                input.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                if (!text.contains(marker)) return null
                return parseRecord(text)?.takeIf(accept)
            }
            while (position > 0) {
                val begin = maxOf(0L, position - buffer.size)
                val count = (position - begin).toInt()
                input.seek(begin)
                input.readFully(buffer, 0, count)
                for (index in count - 1 downTo 0) if (buffer[index] == NewLine) {
                    record(begin + index + 1, end)?.let { return@use it }
                    end = begin + index
                }
                position = begin
            }
            record(0, end)
        }

    internal fun lines(file: File, from: Long, skipFirst: Boolean, each: (JsonObject) -> Unit): Long = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
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
                    parseRecord(line.toString(Charsets.UTF_8))?.let(each)
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
