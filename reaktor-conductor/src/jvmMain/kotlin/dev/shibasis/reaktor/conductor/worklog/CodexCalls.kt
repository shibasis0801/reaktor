package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.workspace.BoardRefs
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

object CodexCalls {
    private const val WindowBytes = 4L shl 20
    private const val RecentMillis = 30 * 60_000L
    private const val Days = 30L
    private const val Kept = 64
    private val Rollout = Regex("rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-(.+)\\.jsonl")
    private val Calls = setOf("custom_tool_call", "function_call")
    private val Outputs = setOf("custom_tool_call_output", "function_call_output")
    private val Quotes = listOf('"', '\'', '`')

    private class Scan(val start: Long) {
        var offset = start
        val pending = LinkedHashMap<String, Pair<Long, String>>()
    }

    private val scans = ConcurrentHashMap<String, Scan>()

    data class Call(val thread: String, val rollout: File)

    fun kernelCall(operation: String, home: File = File(System.getProperty("user.home")), now: Long = System.currentTimeMillis()): Call? {
        val named = Quotes.map { "$it$operation$it" }
        return recent(home, now).filter { file -> inFlight(file, now).any { call -> "__kernel" in call && named.any(call::contains) } }
            .mapNotNull { file -> Rollout.matchEntire(file.name)?.groupValues?.get(1)?.let { Call(BoardRefs.Codex + it, file) } }
            .distinctBy { it.thread }.singleOrNull()?.takeIf { BoardRefs.pattern.matches(it.thread) }
    }

    fun shown(rollout: File, from: Long, text: String): Boolean {
        var seen = false
        WorkLogs.lines(rollout, from, skipFirst = false) { record ->
            val payload = record.obj("payload")
            if (!seen && record.str("type") == "response_item" && payload?.str("type") in Outputs && text in payload.toString()) seen = true
        }
        return seen
    }

    fun inFlight(file: File, now: Long): List<String> {
        val length = file.length()
        val scan = scans[file.path]?.takeIf { it.offset <= length } ?: Scan(maxOf(0L, length - WindowBytes)).also {
            if (scans.size >= Kept) scans.clear()
            scans[file.path] = it
        }
        return synchronized(scan) {
            scan.offset = WorkLogs.lines(file, scan.offset, skipFirst = scan.offset == scan.start && scan.start > 0) { scan.see(it) }
            scan.pending.values.filter { (at, _) -> now - at < RecentMillis }.map { it.second }
        }
    }

    private fun Scan.see(record: JsonObject) {
        when (record.str("type")) {
            "turn_context" -> pending.clear()
            "response_item" -> {
                val payload = record.obj("payload") ?: return
                val id = payload.str("call_id") ?: return
                when (payload.str("type")) {
                    in Calls -> pending[id] = (instant(record.str("timestamp")) ?: 0L) to
                        "${payload.str("name").orEmpty()}\n${(payload.str("input") ?: payload.str("arguments")).orEmpty()}"
                    in Outputs -> pending.remove(id)
                }
            }
        }
    }

    private fun recent(home: File, now: Long): List<File> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return (0L..Days).flatMap { back ->
            val day = today.minusDays(back)
            File(home, ".codex/sessions/%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth)).listFiles().orEmpty()
                .filter { it.isFile && now - it.lastModified() < RecentMillis && Rollout.matches(it.name) }
        }
    }
}
