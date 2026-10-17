package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
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
        RuntimeKind.Gemini -> File(home, ".gemini/antigravity-cli/brain/$session/.system_generated/logs/transcript.jsonl").takeIf(File::isFile)
        else -> null
    }

    fun read(runtime: RuntimeKind, file: File, root: String?, whole: Boolean = false): WorkLog {
        if (!file.isFile) return WorkLog.Empty
        val key = "$runtime\u0000${file.path}\u0000$root\u0000$whole"
        val length = file.length()
        val known = scans[key]?.takeIf { it.offset <= length }
        val scan = known ?: Scan(if (whole) maxOf(0L, length - WholeBytes) else maxOf(0L, length - WindowBytes), parser(runtime, file, root)).also {
            if (scans.size >= Kept) scans.clear()
            scans[key] = it
        }
        return synchronized(scan) {
            val modified = file.lastModified()
            if (scan.length != length || scan.modified != modified) {
                scan.offset = lines(file, scan.offset, skipFirst = scan.offset == scan.start && scan.start > 0) { scan.parser.line(it) }
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
            val payload = record.obj("payload")?.takeIf { it.str("type") == "token_count" }
            val limits = payload?.obj("rate_limits")
            val windows = listOfNotNull(limits?.obj("primary"), limits?.obj("secondary")).mapNotNull { window ->
                window.text("used_percent")?.toDoubleOrNull()?.let { RateWindow(it, window.num("window_minutes"), window.num("resets_at")?.times(1000)) }
            }
            if (windows.isNotEmpty()) found = RateLimits(windows, limits?.str("plan_type"), instant(record.str("timestamp")))
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

    private fun lines(file: File, from: Long, skipFirst: Boolean, each: (JsonObject) -> Unit): Long = RandomAccessFile(file, "r").use { raf ->
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
