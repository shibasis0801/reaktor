package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.worklog.parsedArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class HarnessStep(val at: Long?, val tool: String, val detail: String?, val door: Boolean, val file: String? = null)

data class HarnessEdit(val file: String, val at: Long?)

enum class HarnessActivity { NeedsYou, Working, Waiting, Ended }

data class HarnessSession(
    val runtime: RuntimeKind,
    val id: String,
    val title: String,
    val directory: String,
    val startedAt: Long?,
    val lastActiveAt: Long,
    val origin: String?,
    val version: String?,
    val branch: String?,
    val model: String?,
    val effort: String?,
    val tokens: Long?,
    val steps: List<HarnessStep>,
    val transcript: String,
    val files: List<String> = steps.mapNotNull { it.file }.distinct(),
    val activity: HarnessActivity = HarnessActivity.Ended,
    val status: String? = null,
    val pid: Long? = null,
) {
    val doorCalls: Int get() = steps.count { it.door }

    fun live(now: Long): Boolean = now - lastActiveAt <= LiveMillis

    fun resumeCommand(): String = when (runtime) {
        RuntimeKind.Codex -> "codex resume $id"
        RuntimeKind.Gemini -> "agy --conversation $id"
        else -> "claude --resume $id"
    }

    companion object {
        const val LiveMillis: Long = 120_000
    }
}

enum class HarnessEntryKind { Prompt, Reply, Tool, Note }

data class HarnessEntry(val at: Long?, val kind: HarnessEntryKind, val text: String, val detail: String? = null, val file: String? = null)

data class HarnessTimeline(val entries: List<HarnessEntry>, val partial: Boolean)

interface HarnessProcesses {
    fun alive(pid: Long): Boolean

    fun openTranscripts(): Set<String>?

    object System : HarnessProcesses {
        private const val OpenMillis = 5_000L

        @Volatile
        private var open: Pair<Long, Set<String>?>? = null

        override fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        override fun openTranscripts(): Set<String>? {
            val now = java.lang.System.currentTimeMillis()
            open?.takeIf { now - it.first < OpenMillis }?.let { return it.second }
            return listOpen().also { open = now to it }
        }

        private fun listOpen(): Set<String>? = runCatching {
            val codex = ProcessHandle.allProcesses()
                .filter { process -> process.info().command().orElse("").substringAfterLast('/').startsWith("codex") }
                .map { it.pid() }.toList()
            if (codex.isEmpty()) return@runCatching emptySet<String>()
            val lsof = ProcessBuilder("lsof", "-n", "-P", "-Fn", "-p", codex.joinToString(","))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val names = lsof.inputStream.bufferedReader().readLines()
            lsof.waitFor(5, TimeUnit.SECONDS)
            names.filter { it.startsWith("n") && it.endsWith(".jsonl") }.map { it.drop(1) }.toSet()
        }.getOrNull()
    }
}

object HarnessSessions {
    private const val WindowMillis = 72 * 3_600_000L
    private const val HeadBytes = 256 * 1024
    private const val TailBytes = 768 * 1024
    private const val TimelineBytes = 4 * 1024 * 1024
    private const val StepsKept = 40
    private const val EntriesKept = 400
    private const val EntryChars = 16_000
    private const val ParsedKept = 2_048
    private const val ScanBytes = 1 shl 20
    private const val NewLine = '\n'.code.toByte()
    private val ApprovalEvents = setOf("exec_approval_request", "apply_patch_approval_request", "request_user_input")
    private val EditTools = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
    private val ClaudeTool = "\"name\":\"".encodeToByteArray()
    private val ClaudeEdits = EditTools.map { "$it\"".encodeToByteArray() }
    private val CodexItem = "\"type\":\"".encodeToByteArray()
    private val CodexEdits = listOf("FileChange\"".encodeToByteArray())
    private val AntigravityEdits = listOf("replace_file_content\"", "multi_replace_file_content\"", "write_to_file\"").map { it.encodeToByteArray() }
    private val AntigravityEditTools = setOf("replace_file_content", "multi_replace_file_content", "write_to_file")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(
        workspace: File,
        home: File = File(System.getProperty("user.home")),
        now: Long = System.currentTimeMillis(),
        processes: HarnessProcesses = HarnessProcesses.System,
    ): List<HarnessSession> {
        val root = workspace.canonicalPath
        val since = now - WindowMillis
        return (claude(home, since, now, root, processes) + codex(home, since, now, root, processes.openTranscripts()) + antigravity(home, since, now, root))
            .sortedByDescending { it.lastActiveAt }.distinctBy { it.runtime to it.id }
    }

    fun stamp(home: File = File(System.getProperty("user.home")), now: Long = System.currentTimeMillis()): Long {
        val since = now - WindowMillis
        val registry = File(home, ".claude/sessions").listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().toList()
        return (registry + claudeFiles(home, since) + codexFiles(home, since, now) + antigravityFiles(home, since))
            .sumOf { file -> (file.path.hashCode() * 31L + file.lastModified()) * 31L + file.length() }
    }

    fun timeline(session: HarnessSession, workspace: File): HarnessTimeline {
        val file = File(session.transcript)
        if (session.transcript.isBlank() || !file.isFile) return HarnessTimeline(emptyList(), false)
        val root = workspace.canonicalPath
        val records = records(file, fromEnd = true, TimelineBytes)
        val entries = when (session.runtime) {
            RuntimeKind.Codex -> records.flatMap { codexEntries(it, root) }
            RuntimeKind.Gemini -> records.flatMap { antigravityEntries(it, root) }
            else -> records.flatMap { claudeEntries(it, root) }
        }
        return HarnessTimeline(entries.takeLast(EntriesKept), file.length() > TimelineBytes || entries.size > EntriesKept)
    }

    fun edited(session: HarnessSession, workspace: File): List<HarnessEdit> {
        val file = File(session.transcript)
        if (session.transcript.isBlank() || !file.isFile) return LinkedHashMap<String, Long?>().apply { add(session.steps) }.edits()
        val root = workspace.canonicalPath
        val key = "$root\u0000${file.path}"
        val known = editScans[key]?.takeIf { it.offset <= file.length() } ?: EditScan(0, emptyList())
        val latest = LinkedHashMap<String, Long?>().apply { known.edits.forEach { put(it.file, it.at) } }
        val (prefix, names) = when (session.runtime) {
            RuntimeKind.Codex -> CodexItem to CodexEdits
            RuntimeKind.Gemini -> ClaudeTool to AntigravityEdits
            else -> ClaudeTool to ClaudeEdits
        }
        val offset = lines(file, known.offset) { line ->
            if (line.mentions(prefix, names)) parse(line.decodeToString())?.let { latest.add(steps(it, session.runtime, root)) }
        }
        val edits = latest.edits()
        if (editScans.size >= ParsedKept) editScans.clear()
        editScans[key] = EditScan(offset, edits)
        return edits
    }

    private class EditScan(val offset: Long, val edits: List<HarnessEdit>)

    private val editScans = ConcurrentHashMap<String, EditScan>()

    private fun steps(record: JsonObject, runtime: RuntimeKind, root: String): List<HarnessStep> {
        val at = instant(record.string("timestamp"))
        return when (runtime) {
            RuntimeKind.Codex -> record.nested("payload")?.takeIf { it.string("type") == "item_completed" }?.let { codexSteps(it.nested("item"), at, root) }.orEmpty()
            RuntimeKind.Gemini -> antigravitySteps(record, root)
            else -> (record.nested("message")?.get("content") as? JsonArray).orEmpty()
                .mapNotNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "tool_use" }?.let { claudeStep(it, at, root) } }
        }
    }

    private fun MutableMap<String, Long?>.add(steps: List<HarnessStep>) =
        steps.forEach { step -> step.file?.let { path -> this[path] = listOfNotNull(this[path], step.at).maxOrNull() } }

    private fun Map<String, Long?>.edits(): List<HarnessEdit> = map { (path, at) -> HarnessEdit(path, at) }

    private fun lines(file: File, from: Long, each: (ByteArray) -> Unit): Long = file.inputStream().use { input ->
        input.skipNBytes(from)
        val buffer = ByteArray(ScanBytes)
        val pending = java.io.ByteArrayOutputStream()
        var position = from
        var complete = from
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            var start = 0
            for (index in 0 until read) {
                if (buffer[index] != NewLine) continue
                if (pending.size() == 0) each(buffer.copyOfRange(start, index))
                else each(pending.apply { write(buffer, start, index - start) }.toByteArray().also { pending.reset() })
                start = index + 1
                complete = position + index + 1
            }
            pending.write(buffer, start, read - start)
            position += read
        }
        complete
    }

    private fun ByteArray.mentions(prefix: ByteArray, names: List<ByteArray>): Boolean {
        var at = find(prefix, 0)
        while (at >= 0) {
            val after = at + prefix.size
            if (names.any { matches(it, after) }) return true
            at = find(prefix, after)
        }
        return false
    }

    private fun ByteArray.find(marker: ByteArray, from: Int): Int {
        for (index in from..size - marker.size) if (matches(marker, index)) return index
        return -1
    }

    private fun ByteArray.matches(marker: ByteArray, at: Int): Boolean {
        if (at + marker.size > size) return false
        for (offset in marker.indices) if (this[at + offset] != marker[offset]) return false
        return true
    }

    private class ClaudeLive(
        val pid: Long, val sessionId: String, val cwd: String, val status: String?, val name: String?,
        val entrypoint: String?, val version: String?, val startedAt: Long?, val updatedAt: Long?,
    ) {
        val activity get() = when (status) {
            null, "idle" -> HarnessActivity.Waiting
            else -> HarnessActivity.Working
        }
    }

    private fun claudeLive(home: File, root: String, processes: HarnessProcesses): Map<String, ClaudeLive> =
        File(home, ".claude/sessions").listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().mapNotNull { file ->
            val entry = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val pid = entry.text("pid")?.toLongOrNull() ?: return@mapNotNull null
            val cwd = entry.text("cwd") ?: return@mapNotNull null
            val sessionId = entry.text("sessionId") ?: return@mapNotNull null
            if (!related(cwd, root) || !processes.alive(pid)) return@mapNotNull null
            ClaudeLive(pid, sessionId, cwd, entry.text("status"), entry.text("name"), entry.text("entrypoint"), entry.text("version"),
                entry.text("startedAt")?.toLongOrNull(), (entry.text("statusUpdatedAt") ?: entry.text("updatedAt"))?.toLongOrNull())
        }.associateBy { it.sessionId }

    private fun claude(home: File, since: Long, now: Long, root: String, processes: HarnessProcesses): List<HarnessSession> {
        val live = claudeLive(home, root, processes)
        val read = claudeFiles(home, since).mapNotNull { file -> runCatching { claudeSession(file, root, live, now) }.getOrNull() }
        val seen = read.map { it.id }.toSet()
        return read + live.values.filter { it.sessionId !in seen }.map { entry ->
            HarnessSession(
                runtime = RuntimeKind.ClaudeCode,
                id = entry.sessionId,
                title = entry.name ?: "Untitled session",
                directory = entry.cwd,
                startedAt = entry.startedAt,
                lastActiveAt = entry.updatedAt ?: entry.startedAt ?: now,
                origin = entry.entrypoint,
                version = entry.version,
                branch = null,
                model = null,
                effort = null,
                tokens = null,
                steps = emptyList(),
                transcript = "",
                activity = entry.activity,
                status = entry.status,
                pid = entry.pid,
            )
        }
    }

    private class ClaudeParsed(val session: HarnessSession, val customTitle: String?, val prompt: String?)

    private fun claudeSession(file: File, root: String, live: Map<String, ClaudeLive>, now: Long): HarnessSession? {
        val base = parsed(file, root) { claudeParse(file, root) } ?: return null
        val running = live[base.session.id]
        return base.session.copy(
            title = (base.customTitle ?: running?.name ?: base.prompt ?: "Untitled session").oneLine(),
            origin = running?.entrypoint ?: base.session.origin,
            activity = running?.activity ?: if (now - base.session.lastActiveAt <= HarnessSession.LiveMillis) HarnessActivity.Working else HarnessActivity.Ended,
            status = running?.status,
            pid = running?.pid,
        )
    }

    private fun claudeParse(file: File, root: String): ClaudeParsed? {
        val head = records(file, fromEnd = false, HeadBytes)
        val tail = records(file, fromEnd = true, TailBytes)
        val meta = head.firstOrNull { it.string("cwd") != null } ?: tail.firstOrNull { it.string("cwd") != null } ?: return null
        val directory = meta.string("cwd") ?: return null
        if (!related(directory, root)) return null
        val id = tail.asReversed().firstNotNullOfOrNull { it.string("sessionId") } ?: file.nameWithoutExtension
        val steps = tail.filter { it.string("type") == "assistant" }.flatMap { record ->
            val at = instant(record.string("timestamp"))
            (record.nested("message")?.get("content") as? JsonArray).orEmpty()
                .mapNotNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "tool_use" }?.let { claudeStep(it, at, root) } }
        }
        return ClaudeParsed(
            HarnessSession(
                runtime = RuntimeKind.ClaudeCode,
                id = id,
                title = "",
                directory = directory,
                startedAt = instant(meta.string("timestamp")),
                lastActiveAt = file.lastModified(),
                origin = meta.string("entrypoint"),
                version = meta.string("version"),
                branch = meta.string("gitBranch"),
                model = tail.mapNotNull { record -> record.takeIf { it.string("type") == "assistant" }?.nested("message")?.string("model") }.lastOrNull { it != "<synthetic>" },
                effort = null,
                tokens = null,
                steps = steps.takeLast(StepsKept),
                transcript = file.path,
                files = steps.mapNotNull { it.file }.distinct(),
            ),
            customTitle = (head + tail).lastOrNull { it.string("type") == "custom-title" }?.string("customTitle"),
            prompt = head.firstNotNullOfOrNull(::claudePrompt),
        )
    }

    private fun claudePrompt(record: JsonObject): String? {
        if (record.string("type") != "user") return null
        val content = record.nested("message")?.get("content")
        val text = (content as? JsonPrimitive)?.contentOrNull
            ?: (content as? JsonArray)?.firstNotNullOfOrNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text") }
        return text?.trim()?.takeUnless { it.isEmpty() || it.startsWith("<") || it.startsWith("Caveat:") }
    }

    private fun claudeStep(block: JsonObject, at: Long?, root: String): HarnessStep {
        val name = block.string("name").orEmpty()
        val input = block.nested("input")
        val path = input?.string("file_path") ?: input?.string("notebook_path")
        val edits = name in EditTools
        val detail = input?.string("description") ?: path?.relativeTo(root) ?: input?.string("command") ?: input?.string("pattern")
            ?: input?.string("url") ?: input?.string("query")
        return HarnessStep(at, name.toolLabel(), detail?.oneLine(), name.startsWith("mcp__reaktor__"), path?.takeIf { edits }?.relativeTo(root))
    }

    private fun claudeEntries(record: JsonObject, root: String): List<HarnessEntry> {
        if (record.text("isSidechain") == "true") return emptyList()
        val at = instant(record.string("timestamp"))
        return when (record.string("type")) {
            "user" -> listOfNotNull(claudePrompt(record)?.let { HarnessEntry(at, HarnessEntryKind.Prompt, it.take(EntryChars)) })
            "assistant" -> (record.nested("message")?.get("content") as? JsonArray).orEmpty().mapNotNull { element ->
                val block = element as? JsonObject ?: return@mapNotNull null
                when (block.string("type")) {
                    "text" -> block.string("text")?.trim()?.takeIf(String::isNotEmpty)?.let { HarnessEntry(at, HarnessEntryKind.Reply, it.take(EntryChars)) }
                    "tool_use" -> claudeStep(block, at, root).let { HarnessEntry(at, HarnessEntryKind.Tool, it.tool, it.detail, it.file) }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun codex(home: File, since: Long, now: Long, root: String, open: Set<String>?): List<HarnessSession> {
        val titles = File(home, ".codex/session_index.jsonl").takeIf(File::isFile)?.readLines().orEmpty()
            .mapNotNull(::parse).mapNotNull { entry -> entry.string("id")?.let { id -> entry.string("thread_name")?.let { id to it } } }.toMap()
        return codexFiles(home, since, now).mapNotNull { file -> runCatching { codexSession(file, titles, root, open, now) }.getOrNull() }
    }

    private fun codexFiles(home: File, since: Long, now: Long): List<File> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return (0L..14L).map { today.minusDays(it) }
            .flatMap { day -> File(home, ".codex/sessions/%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth))
                .listFiles { file -> file.extension == "jsonl" && file.lastModified() >= since }.orEmpty().toList() }
    }

    private fun claudeFiles(home: File, since: Long): List<File> =
        File(home, ".claude/projects").listFiles().orEmpty().filter(File::isDirectory)
            .flatMap { project -> project.listFiles { file -> file.extension == "jsonl" && file.lastModified() >= since }.orEmpty().toList() }

    private class CodexParsed(val session: HarnessSession, val prompt: String?, val turning: Boolean, val asking: Boolean)

    private fun codexSession(file: File, titles: Map<String, String>, root: String, open: Set<String>?, now: Long): HarnessSession? {
        val base = parsed(file, root) { codexParse(file, root) } ?: return null
        val held = open?.contains(file.path)
        val recent = now - base.session.lastActiveAt <= HarnessSession.LiveMillis
        return base.session.copy(
            title = (titles[base.session.id] ?: base.prompt ?: "Untitled session").oneLine(),
            activity = when {
                base.turning && (held == true || recent) -> if (base.asking) HarnessActivity.NeedsYou else HarnessActivity.Working
                !base.turning && held == true -> HarnessActivity.Waiting
                else -> HarnessActivity.Ended
            },
        )
    }

    private fun codexParse(file: File, root: String): CodexParsed? {
        val head = records(file, fromEnd = false, HeadBytes)
        val tail = records(file, fromEnd = true, TailBytes)
        val meta = head.firstOrNull { it.string("type") == "session_meta" }?.nested("payload") ?: return null
        val directory = meta.string("cwd") ?: return null
        val context = (head + tail).lastOrNull { it.string("type") == "turn_context" }?.nested("payload")
        val roots = listOf(directory) + (context?.get("workspace_roots") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (roots.none { related(it, root) }) return null
        val id = meta.string("id") ?: file.nameWithoutExtension
        val items = (head + tail).filter { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "item_completed" }
        val steps = tail.filter { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "item_completed" }
            .flatMap { record -> codexSteps(record.nested("payload")?.nested("item"), instant(record.string("timestamp")), root) }
        val usage = tail.lastOrNull { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "token_count" }
            ?.nested("payload")?.nested("info")?.nested("total_token_usage")
        val events = tail.filter { it.string("type") == "event_msg" }.mapNotNull { it.nested("payload")?.string("type") }
            .filter { it != "token_count" && it != "thread_settings_applied" }
        return CodexParsed(
            HarnessSession(
                runtime = RuntimeKind.Codex,
                id = id,
                title = "",
                directory = directory,
                startedAt = instant(meta.string("timestamp")),
                lastActiveAt = file.lastModified(),
                origin = meta.string("originator"),
                version = meta.string("cli_version"),
                branch = null,
                model = context?.string("model"),
                effort = context?.string("effort"),
                tokens = usage?.long("total_tokens"),
                steps = steps.takeLast(StepsKept),
                transcript = file.path,
                files = steps.mapNotNull { it.file }.distinct(),
            ),
            prompt = items.firstNotNullOfOrNull { record ->
                record.nested("payload")?.nested("item")?.takeIf { it.string("type") == "UserMessage" }?.let(::codexText)
            },
            turning = events.indexOfLast { it == "task_started" } > events.indexOfLast { it == "task_complete" || it == "turn_aborted" },
            asking = events.lastOrNull() in ApprovalEvents,
        )
    }

    private fun antigravity(home: File, since: Long, now: Long, root: String): List<HarnessSession> {
        val base = File(home, ".gemini/antigravity-cli")
        val folders = File(base, "cache/last_conversations.json").takeIf(File::isFile)?.let { runCatching { json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
            .orEmpty().entries.mapNotNull { (folder, id) -> (id as? JsonPrimitive)?.contentOrNull?.let { it to folder } }.toMap()
        return antigravityFiles(home, since).mapNotNull { file ->
            val id = file.parentFile.parentFile.parentFile.name
            runCatching { parsed(file, root) { antigravityParse(file, id, folders[id], root) } }.getOrNull()?.let { session ->
                session.copy(activity = if (now - session.lastActiveAt <= HarnessSession.LiveMillis) HarnessActivity.Working else HarnessActivity.Ended)
            }
        }
    }

    private fun antigravityFiles(home: File, since: Long): List<File> =
        File(home, ".gemini/antigravity-cli/brain").listFiles().orEmpty().map { File(it, ".system_generated/logs/transcript.jsonl") }
            .filter { it.isFile && it.lastModified() >= since }

    private fun antigravityParse(file: File, id: String, folder: String?, root: String): HarnessSession? {
        val head = records(file, fromEnd = false, HeadBytes)
        val tail = records(file, fromEnd = true, TailBytes)
        val calls = (head + tail).flatMap { record -> (record["tool_calls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.nested("args")?.parsedArguments() } }
        val paths = calls.mapNotNull { args -> args.string("Cwd") ?: args.string("TargetFile") ?: args.string("AbsolutePath") ?: args.string("SearchDirectory") }
        if (folder?.let { related(it, root) } != true && paths.none { related(it, root) }) return null
        val steps = tail.flatMap { antigravitySteps(it, root) }
        return HarnessSession(
            runtime = RuntimeKind.Gemini,
            id = id,
            title = head.firstNotNullOfOrNull(::antigravityPrompt)?.oneLine() ?: "Untitled session",
            directory = folder ?: root,
            startedAt = head.firstNotNullOfOrNull { instant(it.string("created_at")) },
            lastActiveAt = file.lastModified(),
            origin = "cli",
            version = null,
            branch = null,
            model = null,
            effort = null,
            tokens = null,
            steps = steps.takeLast(StepsKept),
            transcript = file.path,
            files = steps.mapNotNull { it.file }.distinct(),
        )
    }

    private fun antigravityPrompt(record: JsonObject): String? {
        if (record.string("type") != "USER_INPUT") return null
        val text = record.string("content")?.trim().orEmpty()
        val request = if (text.startsWith("<")) Regex("<USER_REQUEST>(.*?)</USER_REQUEST>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) else text
        return request?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun antigravitySteps(record: JsonObject, root: String): List<HarnessStep> {
        if (record.string("type") != "PLANNER_RESPONSE") return emptyList()
        val at = instant(record.string("created_at"))
        return (record["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { call ->
            val name = call.string("name").orEmpty()
            val args = call.nested("args")?.parsedArguments()
            val path = (args?.string("TargetFile") ?: args?.string("AbsolutePath"))?.relativeTo(root)
            val detail = args?.string("CommandLine") ?: path ?: args?.string("Pattern") ?: args?.string("toolSummary")
            HarnessStep(at, name.replace('_', ' '), detail?.oneLine(), name.startsWith("mcp_reaktor"), path?.takeIf { name in AntigravityEditTools })
        }
    }

    private fun antigravityEntries(record: JsonObject, root: String): List<HarnessEntry> {
        val at = instant(record.string("created_at"))
        return when (record.string("type")) {
            "USER_INPUT" -> listOfNotNull(antigravityPrompt(record)?.let { HarnessEntry(at, HarnessEntryKind.Prompt, it.take(EntryChars)) })
            "PLANNER_RESPONSE" -> listOfNotNull(record.string("content")?.trim()?.takeIf(String::isNotEmpty)?.let { HarnessEntry(at, HarnessEntryKind.Reply, it.take(EntryChars)) }) +
                antigravitySteps(record, root).map { HarnessEntry(at, HarnessEntryKind.Tool, it.tool, it.detail, it.file) }
            else -> emptyList()
        }
    }

    private fun codexText(item: JsonObject): String? = (item["content"] as? JsonArray).orEmpty()
        .firstNotNullOfOrNull { block -> (block as? JsonObject)?.string("text") }?.trim()?.takeUnless { it.isEmpty() || it.startsWith("<") }

    private fun codexCommand(item: JsonObject): String? =
        (item["parsed_cmd"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("cmd") }.joinToString(" && ").takeIf(String::isNotBlank)
            ?: ((item["command"] as? JsonArray)?.lastOrNull() as? JsonPrimitive)?.contentOrNull

    private fun codexSteps(item: JsonObject?, at: Long?, root: String): List<HarnessStep> = when (item?.string("type")) {
        "McpToolCall" -> listOf(HarnessStep(at, "${item.string("server")} · ${item.string("tool")}", null, item.string("server") == "reaktor"))
        "CommandExecution" -> listOf(HarnessStep(at, "shell", codexCommand(item)?.oneLine(), false))
        "FileChange" -> item.nested("changes")?.keys.orEmpty().map { path -> HarnessStep(at, "edit", path.relativeTo(root), false, path.relativeTo(root)) }
        else -> emptyList()
    }

    private fun codexEntries(record: JsonObject, root: String): List<HarnessEntry> {
        if (record.string("type") != "event_msg") return emptyList()
        val payload = record.nested("payload") ?: return emptyList()
        if (payload.string("type") != "item_completed") return emptyList()
        val item = payload.nested("item") ?: return emptyList()
        val at = instant(record.string("timestamp"))
        return when (item.string("type")) {
            "UserMessage" -> listOfNotNull(codexText(item)?.let { HarnessEntry(at, HarnessEntryKind.Prompt, it.take(EntryChars)) })
            "AgentMessage" -> listOfNotNull(codexText(item)?.let { HarnessEntry(at, HarnessEntryKind.Reply, it.take(EntryChars)) })
            "ContextCompaction" -> listOf(HarnessEntry(at, HarnessEntryKind.Note, "Context compacted"))
            else -> codexSteps(item, at, root).map { HarnessEntry(at, HarnessEntryKind.Tool, it.tool, it.detail, it.file) }
        }
    }

    private fun records(file: File, fromEnd: Boolean, limit: Int): List<JsonObject> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val size = minOf(length, limit.toLong()).toInt()
        val start = if (fromEnd) length - size else 0L
        val bytes = ByteArray(size).also { raf.seek(start); raf.readFully(it) }
        val lines = bytes.decodeToString().split('\n')
        val whole = when {
            fromEnd && start > 0 -> lines.drop(1)
            !fromEnd && size < length -> lines.dropLast(1)
            else -> lines
        }
        whole.mapNotNull(::parse)
    }

    private class Parsed(val modified: Long, val length: Long, val value: Any?)

    private val parsedFiles = ConcurrentHashMap<String, Parsed>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parsed(file: File, root: String, parse: () -> T?): T? {
        val key = "$root\u0000${file.path}"
        val modified = file.lastModified()
        val length = file.length()
        parsedFiles[key]?.takeIf { it.modified == modified && it.length == length }?.let { return it.value as T? }
        val value = parse()
        if (parsedFiles.size >= ParsedKept) parsedFiles.clear()
        parsedFiles[key] = Parsed(modified, length, value)
        return value
    }

    private fun parse(line: String): JsonObject? = line.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun related(directory: String, root: String): Boolean {
        val path = directory.removePrefix("file://").trimEnd('/')
        return path.isNotEmpty() && (path == root || path.startsWith("$root/") || root.startsWith("$path/"))
    }

    private fun instant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun String.relativeTo(root: String): String = removePrefix("$root/")

    private fun String.oneLine(): String = lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(140)

    private fun String.toolLabel(): String = if (startsWith("mcp__")) removePrefix("mcp__").split("__", limit = 2).joinToString(" · ") else this

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.nested(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
