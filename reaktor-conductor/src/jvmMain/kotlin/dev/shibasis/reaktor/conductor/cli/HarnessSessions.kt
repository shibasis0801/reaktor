package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.RuntimeKind
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

data class HarnessStep(val at: Long?, val tool: String, val detail: String?, val door: Boolean, val file: String? = null)

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
) {
    val doorCalls: Int get() = steps.count { it.door }
    val files: List<String> get() = steps.mapNotNull { it.file }.distinct()

    fun live(now: Long): Boolean = now - lastActiveAt <= LiveMillis

    fun resumeCommand(): String = when (runtime) {
        RuntimeKind.Codex -> "codex resume $id"
        else -> "claude --resume $id"
    }

    companion object {
        const val LiveMillis: Long = 120_000
    }
}

object HarnessSessions {
    private const val WindowMillis = 72 * 3_600_000L
    private const val HeadBytes = 256 * 1024
    private const val TailBytes = 768 * 1024
    private const val StepsKept = 40
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(workspace: File, home: File = File(System.getProperty("user.home")), now: Long = System.currentTimeMillis()): List<HarnessSession> {
        val root = workspace.canonicalPath
        val since = now - WindowMillis
        return (claude(home, since, root) + codex(home, since, now, root)).sortedByDescending { it.lastActiveAt }.distinctBy { it.runtime to it.id }
    }

    private fun claude(home: File, since: Long, root: String): List<HarnessSession> =
        File(home, ".claude/projects").listFiles().orEmpty().filter(File::isDirectory)
            .flatMap { project -> project.listFiles { file -> file.extension == "jsonl" && file.lastModified() >= since }.orEmpty().toList() }
            .mapNotNull { file -> runCatching { claudeSession(file, root) }.getOrNull() }

    private fun claudeSession(file: File, root: String): HarnessSession? {
        val head = records(file, fromEnd = false)
        val tail = records(file, fromEnd = true)
        val meta = head.firstOrNull { it.string("cwd") != null } ?: tail.firstOrNull { it.string("cwd") != null } ?: return null
        val directory = meta.string("cwd") ?: return null
        if (!related(directory, root)) return null
        val title = (head + tail).lastOrNull { it.string("type") == "custom-title" }?.string("customTitle")
            ?: head.firstNotNullOfOrNull(::claudePrompt) ?: "Untitled session"
        val steps = tail.filter { it.string("type") == "assistant" }.flatMap { record ->
            val at = instant(record.string("timestamp"))
            (record.nested("message")?.get("content") as? JsonArray).orEmpty()
                .mapNotNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "tool_use" }?.let { claudeStep(it, at, root) } }
        }.takeLast(StepsKept)
        return HarnessSession(
            runtime = RuntimeKind.ClaudeCode,
            id = tail.asReversed().firstNotNullOfOrNull { it.string("sessionId") } ?: file.nameWithoutExtension,
            title = title.oneLine(),
            directory = directory,
            startedAt = instant(meta.string("timestamp")),
            lastActiveAt = file.lastModified(),
            origin = meta.string("entrypoint"),
            version = meta.string("version"),
            branch = meta.string("gitBranch"),
            model = tail.mapNotNull { record -> record.takeIf { it.string("type") == "assistant" }?.nested("message")?.string("model") }.lastOrNull { it != "<synthetic>" },
            effort = null,
            tokens = null,
            steps = steps,
            transcript = file.path,
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
        val edits = name in setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
        val detail = input?.string("description") ?: path?.relativeTo(root) ?: input?.string("command") ?: input?.string("pattern")
            ?: input?.string("url") ?: input?.string("query")
        return HarnessStep(at, name.toolLabel(), detail?.oneLine(), name.startsWith("mcp__reaktor__"), path?.takeIf { edits }?.relativeTo(root))
    }

    private fun codex(home: File, since: Long, now: Long, root: String): List<HarnessSession> {
        val sessions = File(home, ".codex/sessions")
        val titles = File(home, ".codex/session_index.jsonl").takeIf(File::isFile)?.readLines().orEmpty()
            .mapNotNull(::parse).mapNotNull { entry -> entry.string("id")?.let { id -> entry.string("thread_name")?.let { id to it } } }.toMap()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return (0L..14L).map { today.minusDays(it) }
            .flatMap { day -> File(sessions, "%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth))
                .listFiles { file -> file.extension == "jsonl" && file.lastModified() >= since }.orEmpty().toList() }
            .mapNotNull { file -> runCatching { codexSession(file, titles, root) }.getOrNull() }
    }

    private fun codexSession(file: File, titles: Map<String, String>, root: String): HarnessSession? {
        val head = records(file, fromEnd = false)
        val tail = records(file, fromEnd = true)
        val meta = head.firstOrNull { it.string("type") == "session_meta" }?.nested("payload") ?: return null
        val directory = meta.string("cwd") ?: return null
        val context = (head + tail).lastOrNull { it.string("type") == "turn_context" }?.nested("payload")
        val roots = listOf(directory) + (context?.get("workspace_roots") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (roots.none { related(it, root) }) return null
        val id = meta.string("id") ?: file.nameWithoutExtension
        val items = (head + tail).filter { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "item_completed" }
        val title = titles[id] ?: items.firstNotNullOfOrNull { record ->
            record.nested("payload")?.nested("item")?.takeIf { it.string("type") == "UserMessage" }?.let(::codexText)
        } ?: "Untitled session"
        val steps = tail.filter { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "item_completed" }
            .flatMap { record -> codexSteps(record.nested("payload")?.nested("item"), instant(record.string("timestamp")), root) }
            .takeLast(StepsKept)
        val usage = tail.lastOrNull { it.string("type") == "event_msg" && it.nested("payload")?.string("type") == "token_count" }
            ?.nested("payload")?.nested("info")?.nested("total_token_usage")
        return HarnessSession(
            runtime = RuntimeKind.Codex,
            id = id,
            title = title.oneLine(),
            directory = directory,
            startedAt = instant(meta.string("timestamp")),
            lastActiveAt = file.lastModified(),
            origin = meta.string("originator"),
            version = meta.string("cli_version"),
            branch = null,
            model = context?.string("model"),
            effort = context?.string("effort"),
            tokens = usage?.long("total_tokens"),
            steps = steps,
            transcript = file.path,
        )
    }

    private fun codexText(item: JsonObject): String? = (item["content"] as? JsonArray).orEmpty()
        .firstNotNullOfOrNull { block -> (block as? JsonObject)?.string("text") }?.trim()?.takeUnless { it.isEmpty() || it.startsWith("<") }

    private fun codexSteps(item: JsonObject?, at: Long?, root: String): List<HarnessStep> = when (item?.string("type")) {
        "McpToolCall" -> listOf(HarnessStep(at, "${item.string("server")} · ${item.string("tool")}", null, item.string("server") == "reaktor"))
        "CommandExecution" -> listOf(HarnessStep(at, "shell", ((item["command"] as? JsonArray)?.lastOrNull() as? JsonPrimitive)?.contentOrNull?.oneLine(), false))
        "FileChange" -> item.nested("changes")?.keys.orEmpty().map { path -> HarnessStep(at, "edit", path.relativeTo(root), false, path.relativeTo(root)) }
        else -> emptyList()
    }

    private fun records(file: File, fromEnd: Boolean): List<JsonObject> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val size = minOf(length, (if (fromEnd) TailBytes else HeadBytes).toLong()).toInt()
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

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.nested(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
