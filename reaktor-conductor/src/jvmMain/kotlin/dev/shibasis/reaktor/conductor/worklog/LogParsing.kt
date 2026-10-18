package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

internal interface LogParser {
    fun line(record: JsonObject)
    fun log(earlier: Boolean): WorkLog
}

internal class TurnBuilder(val id: String, val prompt: String?, val startedAt: Long?) {
    val steps = mutableListOf<WorkStep>()
    var endedAt: Long? = startedAt
    var usage: WorkUsage? = null
    var open = true

    fun add(step: WorkStep): Int {
        steps += step
        touch(step.at)
        return steps.lastIndex
    }

    fun settle(index: Int) {
        steps[index] = steps[index].settled()
    }

    fun touch(at: Long?) {
        if (at != null && (endedAt == null || at > endedAt!!)) endedAt = at
    }

    fun use(next: WorkUsage) {
        usage = usage?.plus(next) ?: next
    }

    fun build() = WorkTurn(id, prompt, startedAt, endedAt, steps.toList(), usage, open)
}

internal class Turns(private val prefix: String) {
    private val turns = mutableListOf<TurnBuilder>()

    val current: TurnBuilder? get() = turns.lastOrNull()

    fun start(prompt: String?, at: Long?): TurnBuilder {
        turns.lastOrNull()?.open = false
        return TurnBuilder("$prefix${turns.size}", prompt, at).also { turns += it }
    }

    fun ensure(at: Long?): TurnBuilder = current ?: start(null, at)

    fun at(index: Int): TurnBuilder = turns[index]

    val lastIndex: Int get() = turns.lastIndex

    fun build(earlier: Boolean) = WorkLog(turns.map(TurnBuilder::build), earlier)
}

internal object LogText {
    private const val Head = 2_000
    private const val Tail = 4_000
    const val InputChars = 4_000
    const val DiffLines = 400

    fun cut(text: String?): Pair<String?, Boolean> {
        if (text == null) return null to false
        if (text.length <= Head + Tail) return text to false
        return text.take(Head) + "\n…\n" + text.takeLast(Tail) to true
    }

    fun clip(text: String?, limit: Int = InputChars): String? = text?.let { if (it.length <= limit) it else it.take(limit) + "…" }

    fun oneLine(text: String?): String? = text?.lineSequence()?.map(String::trim)?.firstOrNull { it.isNotEmpty() }?.take(200)

    fun lines(text: String, prefix: Char): Pair<List<String>, Boolean> {
        val all = text.removeSuffix("\n").split('\n')
        return all.take(DiffLines).map { "$prefix$it" } to (all.size > DiffLines)
    }

    fun replacement(old: String, new: String): Pair<DiffHunk, Boolean> {
        val (removed, cutOld) = lines(old, '-')
        val (added, cutNew) = lines(new, '+')
        return DiffHunk(null, null, removed + added) to (cutOld || cutNew)
    }

    fun unified(diff: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        var oldStart: Int? = null
        var newStart: Int? = null
        var lines = mutableListOf<String>()
        fun flush() {
            if (lines.isNotEmpty()) hunks += DiffHunk(oldStart, newStart, lines.toList())
            lines = mutableListOf()
        }
        diff.lineSequence().forEach { line ->
            val header = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(line)
            when {
                header != null -> { flush(); oldStart = header.groupValues[1].toInt(); newStart = header.groupValues[2].toInt() }
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> Unit
                line.startsWith('+') || line.startsWith('-') || line.startsWith(' ') -> if (lines.size < DiffLines) lines += line
                line.startsWith("\\") -> Unit
            }
        }
        flush()
        return hunks
    }

    fun relative(path: String?, root: String?): String? = path?.let { if (root != null && it.startsWith("$root/")) it.removePrefix("$root/") else it }
}

internal val LogJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseRecord(line: String): JsonObject? =
    line.takeIf { it.isNotBlank() }?.let { runCatching { LogJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

internal fun JsonObject.parsedArguments(): JsonObject = JsonObject(mapValues { (_, value) -> value.parsedJsonText() ?: value })

private val JsonTextStarts = setOf('"', '[', '{')

private fun JsonElement.parsedJsonText(): JsonElement? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.firstOrNull() in JsonTextStarts }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

internal fun JsonElement?.plain(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { element -> (element as? JsonObject)?.let { it.str("text") ?: it.str("content") } ?: (element as? JsonPrimitive)?.contentOrNull }
        .joinToString("\n").takeIf(String::isNotEmpty)
    is JsonObject -> str("text") ?: toString()
}

internal fun instant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
