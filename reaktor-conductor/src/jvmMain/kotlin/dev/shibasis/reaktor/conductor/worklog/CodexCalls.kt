package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.workspace.BoardRefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CodexCalls {
    private const val WindowBytes = 4L shl 20
    private const val RecentMillis = 30 * 60_000L
    private const val Kept = 64
    private val Rollout = Regex("rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-(.+)\\.jsonl")
    private val Calls = setOf("custom_tool_call", "function_call")
    private val Outputs = setOf("custom_tool_call_output", "function_call_output")

    private class Scan(val start: Long) {
        var offset = start
        val pending = LinkedHashMap<String, Long>()
    }

    private val scans = ConcurrentHashMap<String, Scan>()

    data class Caller(val thread: String?, val diagnostic: String? = null)

    fun caller(metadata: JsonObject, process: ProcessHandle? = codexProcess()): Caller {
        if ("threadId" in metadata) return resolve(metadata, emptyList(), false)
        if (process == null || !process.isAlive) return Caller(null, "No live calling Codex process or MCP threadId")
        val files = open(process.pid()) ?: return Caller(null, "Cannot inspect the calling Codex process's open rollouts")
        if (!process.isAlive) return Caller(null, "Calling Codex process exited during rollout inspection")
        return resolve(metadata, files, process.info().arguments().orElse(emptyArray()).firstOrNull() == "exec")
    }

    internal fun resolve(metadata: JsonObject, files: List<File>, singleThread: Boolean, now: Long = System.currentTimeMillis()): Caller {
        if ("threadId" in metadata) {
            val id = (metadata["threadId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val thread = id?.let { BoardRefs.Codex + it }?.takeIf(BoardRefs.pattern::matches)
            return Caller(thread, if (thread == null) "Invalid Codex MCP threadId; no fallback identity used" else null)
        }
        val rollouts = files.distinctBy { it.absolutePath }.mapNotNull { file ->
            Rollout.matchEntire(file.name)?.groupValues?.get(1)?.let { BoardRefs.Codex + it }
                ?.takeIf(BoardRefs.pattern::matches)?.let { it to file }
        }
        if (singleThread && rollouts.size == 1) return Caller(rollouts.single().first)
        val id = (metadata["callId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
        if (id != null) {
            val matches = runCatching { rollouts.filter { (_, file) -> id in pending(file, now) } }
                .getOrElse { return Caller(null, "Cannot read all rollouts held by the calling Codex process") }
            if (matches.size == 1) return Caller(matches.single().first)
            return Caller(null, "MCP callId matches ${matches.size} rollouts held by the calling Codex process")
        }
        return Caller(null, "Calling Codex process holds ${rollouts.size} rollouts; MCP threadId or exact callId is required")
    }

    private fun codexProcess(): ProcessHandle? = generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
        .take(8).firstOrNull { it.isAlive && File(it.info().command().orElse("")).name in setOf("codex", "codex.exe") }

    private fun open(pid: Long): List<File>? = runCatching {
        val process = ProcessBuilder("lsof", "-n", "-P", "-Fn", "-p", "$pid").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readLines() } }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
            output.get(1, TimeUnit.SECONDS).filter { it.startsWith("n") }.map { File(it.drop(1)) }
                .filter { Rollout.matches(it.name) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrNull()

    fun shown(rollout: File, from: Long, text: String): Boolean {
        var seen = false
        WorkLogs.lines(rollout, from, skipFirst = false) { record ->
            val payload = record.obj("payload")
            if (!seen && record.str("type") == "response_item" && payload?.str("type") in Outputs && text in payload.toString()) seen = true
        }
        return seen
    }

    private fun pending(file: File, now: Long): Set<String> {
        val length = file.length()
        val scan = scans[file.path]?.takeIf { it.offset <= length } ?: Scan(maxOf(0L, length - WindowBytes)).also {
            if (scans.size >= Kept) scans.clear()
            scans[file.path] = it
        }
        return synchronized(scan) {
            scan.offset = WorkLogs.lines(file, scan.offset, skipFirst = scan.offset == scan.start && scan.start > 0) { scan.see(it) }
            scan.pending.filterValues { at -> now - at < RecentMillis }.keys.toSet()
        }
    }

    private fun Scan.see(record: JsonObject) {
        when (record.str("type")) {
            "turn_context" -> pending.clear()
            "response_item" -> {
                val payload = record.obj("payload") ?: return
                val id = payload.str("call_id") ?: return
                when (payload.str("type")) {
                    in Calls -> pending[id] = instant(record.str("timestamp")) ?: 0L
                    in Outputs -> pending.remove(id)
                }
            }
        }
    }
}
