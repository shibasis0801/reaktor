package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.ContextHeadings
import dev.shibasis.reaktor.conductor.PendingQuestion
import kotlinx.serialization.json.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.ContextPacket
import dev.shibasis.reaktor.conductor.ContextEntry

@Serializable
data class WorkLog(val turns: List<WorkTurn>, val earlier: Boolean = false) {
    val edits: List<WorkStep.Edit> get() = turns.flatMap { turn -> turn.steps.flatMap(WorkStep::edits) }
    val files: List<String> get() = edits.map { it.file }.distinct()
    val running: Boolean get() = turns.lastOrNull()?.open == true

    companion object {
        val Empty = WorkLog(emptyList())
    }
}

@Serializable
data class WorkTurn(
    val id: String,
    val prompt: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val steps: List<WorkStep>,
    val usage: WorkUsage? = null,
    val open: Boolean = false,
    val usageSamples: List<WorkUsageSample> = emptyList(),
    val context: WorkContext? = null,
) {
    val files: List<String> get() = steps.flatMap(WorkStep::edits).map { it.file }.distinct()
    val millis: Long? get() = startedAt?.let { start -> endedAt?.minus(start)?.takeIf { it >= 0 } }
}

@Serializable
data class WorkContextSlice(val title: String, val text: String, val tokenEstimate: Int = (text.length + 3) / 4)

@Serializable
data class WorkContext(
    val sent: String,
    val slices: List<WorkContextSlice>,
    val tokenEstimate: Int,
    val omittedEntries: Int = 0,
    val notices: List<String> = emptyList(),
)

data class WorkPrompt(val text: String?, val context: WorkContext? = null)

object WorkPrompts {
    private val Role = Regex("\\n\\nYour role, [^\\r\\n:]+: ")

    private fun taskBoundary(text: String, start: Int): Boolean = start == 0 ||
        start >= 2 && text[start - 2] == '\n' || start >= 3 && text[start - 2] == '\r' && text[start - 3] == '\n'

    internal fun isEnvelope(text: String?): Boolean = text != null && text.startsWith("You are ") &&
        text.substringBefore('\n').trimEnd('\r').endsWith('.') &&
        generateSequence(ContextHeadings.find(text)) { ContextHeadings.find(text, it.end) }
            .any { it.heading == ContextHeadings.Task && taskBoundary(text, it.start) }

    fun of(raw: String?, profile: Boolean = false): WorkPrompt {
        if (raw == null) return WorkPrompt(null)
        val slices = mutableListOf<WorkContextSlice>()
        var task = raw
        var envelopeChars = 0
        var omitted = 0
        val notices = mutableListOf<String>()
        val lines = generateSequence(ContextHeadings.find(raw)) { ContextHeadings.find(raw, it.end) }
        val boundary = lines.firstOrNull { it.heading == ContextHeadings.Task && taskBoundary(raw, it.start) }
        if (raw.startsWith("You are ") && raw.substringBefore('\n').trimEnd('\r').endsWith('.') && boundary != null) {
            val prefix = raw.substring(0, boundary.start)
            envelopeChars += boundary.end
            task = raw.substring(boundary.end).trimEnd('\r', '\n')
            val headings = generateSequence(ContextHeadings.find(prefix)) { ContextHeadings.find(prefix, it.end) }
                .filter { it.heading != ContextHeadings.Task }.toList()
            prefix.substring(0, headings.firstOrNull()?.start ?: prefix.length).trim().takeIf(String::isNotEmpty)?.let {
                slices += WorkContextSlice("Instructions", it)
            }
            headings.forEachIndexed { index, heading ->
                val text = prefix.substring(heading.end, headings.getOrNull(index + 1)?.start ?: prefix.length).trim()
                val packet = if (heading.heading == ContextHeadings.Evidence) runCatching {
                    ConductorJson.decodeFromString(ContextPacket.serializer(), text)
                }.getOrNull() else null
                if (packet == null) slices += WorkContextSlice(heading.heading.removePrefix("## "), text)
                else {
                    slices += WorkContextSlice("Evidence scope", ConductorJson.encodeToString(ContextPacket.serializer(), packet.copy(entries = emptyList())))
                    slices += packet.entries.map { WorkContextSlice(it.title, ConductorJson.encodeToString(ContextEntry.serializer(), it)) }
                    omitted += packet.omittedEntries
                    notices += packet.notices
                }
            }
        }
        if (profile) Role.findAll(task).lastOrNull()?.let { role ->
            slices += WorkContextSlice("Profile role", task.substring(role.range.first).trim())
            envelopeChars += task.length - role.range.first
            task = task.substring(0, role.range.first)
        }
        return if (slices.isEmpty()) WorkPrompt(raw) else WorkPrompt(task,
            WorkContext(raw, slices, (envelopeChars + 3) / 4, omitted, notices))
    }
}

@Serializable
data class WorkUsageSample(
    val id: String? = null,
    val at: Long? = null,
    val context: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val input: Long? = null,
    val costUsd: Double? = null,
    val cached: Long? = null,
    val fresh: Long? = null,
    val cacheWrite: Long? = null,
    val contextWindow: Long? = null,
    val estimatedCostUsd: Double? = null,
    val model: String? = null,
) {
    fun toWorkUsage(): WorkUsage = WorkUsage(
        context = context,
        output = output,
        reasoning = reasoning,
        input = input,
        costUsd = costUsd,
        cached = cached,
        fresh = fresh,
        cacheWrite = cacheWrite,
        peakContext = context,
        contextWindow = contextWindow,
        estimatedCostUsd = estimatedCostUsd,
    )
}

@Serializable
data class WorkUsage(
    val context: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val input: Long? = null,
    val costUsd: Double? = null,
    val cached: Long? = null,
    val fresh: Long? = null,
    val cacheWrite: Long? = null,
    val peakContext: Long? = null,
    val contextWindow: Long? = null,
    val estimatedCostUsd: Double? = null,
) {
    operator fun plus(next: WorkUsage) = WorkUsage(
        context = next.context ?: context,
        output = sum(output, next.output),
        reasoning = sum(reasoning, next.reasoning),
        input = sum(input, next.input),
        costUsd = sumDouble(costUsd, next.costUsd),
        cached = sum(cached, next.cached),
        fresh = sum(fresh, next.fresh),
        cacheWrite = sum(cacheWrite, next.cacheWrite),
        peakContext = listOfNotNull(peakContext ?: context, next.peakContext ?: next.context).maxOrNull(),
        contextWindow = next.contextWindow ?: contextWindow,
        estimatedCostUsd = sumDouble(estimatedCostUsd, next.estimatedCostUsd),
    )

    private fun sum(a: Long?, b: Long?) = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
    private fun sumDouble(a: Double?, b: Double?) = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}

@Serializable
data class RateWindow(val usedPercent: Double, val minutes: Long? = null, val resetsAt: Long? = null)

@Serializable
data class RateLimits(
    val windows: List<RateWindow> = emptyList(),
    val plan: String? = null,
    val at: Long? = null,
    val status: String? = null,
    val resetsAt: Long? = null,
    val overage: Boolean = false,
)

@Serializable
enum class StepStatus { Running, Done, Failed, Interrupted }

@Serializable
enum class ToolKind { Read, Search, Web, Mcp, Other }

@Serializable
enum class EditKind { Add, Update, Delete }

@Serializable
enum class PlanState { Pending, Active, Done }

@Serializable
enum class NoteKind { Compaction, Interrupted, Error, Checkpoint, Info }

@Serializable
data class DiffHunk(val oldStart: Int?, val newStart: Int?, val lines: List<String>)

@Serializable
data class PlanItem(val text: String, val state: PlanState)

@Serializable
sealed interface WorkStep {
    val id: String
    val at: Long?
    val status: StepStatus get() = StepStatus.Done

    @Serializable @SerialName("message")
    data class Message(override val id: String, override val at: Long?, val text: String, val author: String? = null) : WorkStep

    @Serializable @SerialName("reasoning")
    data class Reasoning(override val id: String, override val at: Long?, val text: String, val summary: Boolean, val millis: Long? = null) : WorkStep

    @Serializable @SerialName("command")
    data class Command(
        override val id: String,
        override val at: Long?,
        val command: String,
        val purpose: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        override val status: StepStatus = StepStatus.Running,
        val millis: Long? = null,
        val cut: Boolean = false,
    ) : WorkStep

    @Serializable @SerialName("edit")
    data class Edit(
        override val id: String,
        override val at: Long?,
        val file: String,
        val kind: EditKind,
        val hunks: List<DiffHunk>,
        override val status: StepStatus = StepStatus.Running,
        val cut: Boolean = false,
        val movedTo: String? = null,
        /** Complete recorded contents after a write, or before a deletion. Never read from the current worktree. */
        val content: String? = null,
    ) : WorkStep {
        val added: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.startsWith('+') } }
        val removed: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.startsWith('-') } }
        val line: Int? get() = hunks.firstOrNull()?.newStart
    }

    @Serializable @SerialName("tool")
    data class Tool(
        override val id: String,
        override val at: Long?,
        val kind: ToolKind,
        val name: String,
        val summary: String? = null,
        val input: String? = null,
        val output: String? = null,
        override val status: StepStatus = StepStatus.Running,
        val millis: Long? = null,
        val server: String? = null,
        val cut: Boolean = false,
    ) : WorkStep

    @Serializable @SerialName("plan")
    data class Plan(override val id: String, override val at: Long?, val items: List<PlanItem>, val note: String? = null) : WorkStep

    @Serializable @SerialName("subagent")
    data class Subagent(
        override val id: String,
        override val at: Long?,
        val name: String,
        val prompt: String? = null,
        val model: String? = null,
        val steps: List<WorkStep> = emptyList(),
        val result: String? = null,
        override val status: StepStatus = StepStatus.Running,
    ) : WorkStep

    @Serializable @SerialName("question")
    data class Question(override val id: String, override val at: Long?, val text: String, val answer: String? = null, override val status: StepStatus = StepStatus.Running, val answeredAt: Long? = null, val questions: List<PendingQuestion> = emptyList()) : WorkStep

    @Serializable @SerialName("note")
    data class Note(override val id: String, override val at: Long?, val kind: NoteKind, val text: String) : WorkStep
}

fun WorkLog.settled(live: Boolean = false): WorkLog = copy(turns = turns.mapIndexed { index, turn ->
    if (live && index == turns.lastIndex) turn else turn.copy(open = false, steps = turn.steps.map(WorkStep::settled))
})

fun WorkStep.settled(): WorkStep = when (this) {
    is WorkStep.Subagent -> copy(status = status.settled(), steps = steps.map(WorkStep::settled))
    is WorkStep.Command -> copy(status = status.settled())
    is WorkStep.Edit -> copy(status = status.settled())
    is WorkStep.Tool -> copy(status = status.settled())
    is WorkStep.Question -> copy(status = status.settled())
    else -> this
}

private fun StepStatus.settled() = if (this == StepStatus.Running) StepStatus.Interrupted else this

fun WorkStep.edits(): List<WorkStep.Edit> = when (this) {
    is WorkStep.Edit -> listOf(this)
    is WorkStep.Subagent -> steps.flatMap(WorkStep::edits)
    else -> emptyList()
}

data class TurnEdit(val turn: Int, val edit: WorkStep.Edit)

data class FileChange(val file: String, val edits: List<TurnEdit>) {
    val added: Int get() = edits.sumOf { it.edit.added }
    val removed: Int get() = edits.sumOf { it.edit.removed }
    val line: Int? get() = edits.firstNotNullOfOrNull { it.edit.line }
    val movedTo: String? get() = edits.last().edit.movedTo
    val kind: EditKind get() = when {
        edits.last().edit.kind == EditKind.Delete -> EditKind.Delete
        edits.first().edit.kind == EditKind.Add -> EditKind.Add
        else -> EditKind.Update
    }
}

val WorkLog.changes: List<FileChange>
    get() = turns.flatMapIndexed { index, turn -> turn.steps.flatMap(WorkStep::edits).map { TurnEdit(index + 1, it) } }
        .filter { it.edit.status == StepStatus.Done || it.edit.status == StepStatus.Running }
        .groupBy { it.edit.file }
        .map { (file, edits) -> FileChange(file, edits) }

fun WorkLog.slice(from: Long, until: Long): WorkLog {
    fun inside(at: Long?) = at != null && at >= from && at < until
    fun scoped(step: WorkStep): WorkStep? = when (step) {
        is WorkStep.Subagent -> {
            val steps = step.steps.mapNotNull(::scoped)
            if (!inside(step.at) && steps.isEmpty()) null
            else step.copy(
                at = step.at?.takeIf(::inside) ?: steps.firstOrNull()?.at,
                prompt = step.prompt.takeIf { inside(step.at) },
                steps = steps,
                result = null,
            )
        }
        else -> step.takeIf { inside(it.at) }
    }
    val slicedTurns = turns.mapNotNull { turn ->
        val steps = turn.steps.mapNotNull(::scoped)
        val samples = turn.usageSamples.filter { inside(it.at) }
        val prompt = turn.prompt.takeIf { inside(turn.startedAt) }
        if (steps.isEmpty() && samples.isEmpty() && prompt == null) return@mapNotNull null
        val wholeTurnInside = inside(turn.startedAt) && (turn.endedAt == null || turn.endedAt in from..until)
        val slicedUsage = when {
            turn.usageSamples.isNotEmpty() -> samples.map(WorkUsageSample::toWorkUsage).reduceOrNull(WorkUsage::plus)
            wholeTurnInside -> turn.usage
            else -> null
        }
        val timestamps = buildList {
            turn.startedAt?.takeIf(::inside)?.let(::add)
            steps.mapNotNull { it.at }.forEach(::add)
            samples.mapNotNull { it.at }.forEach(::add)
            if (wholeTurnInside && turn.endedAt != null) add(turn.endedAt)
        }
        val slicedStart = timestamps.minOrNull() ?: turn.startedAt?.takeIf(::inside)
        val slicedEnd = timestamps.maxOrNull() ?: turn.endedAt?.takeIf { it in from..until }
        turn.copy(
            prompt = prompt,
            startedAt = slicedStart,
            endedAt = slicedEnd,
            steps = steps,
            usage = slicedUsage,
            open = turn.open && until == Long.MAX_VALUE,
            usageSamples = samples,
        )
    }
    return WorkLog(slicedTurns, earlier)
}

fun mergeWorkLogs(logs: List<WorkLog>): WorkLog {
    fun mergeSteps(steps: List<WorkStep>): List<WorkStep> = steps.groupBy { it.id }.values.map { versions ->
        when (val step = versions.last()) {
            is WorkStep.Subagent -> step.copy(steps = mergeSteps(versions.filterIsInstance<WorkStep.Subagent>().flatMap { it.steps }))
            else -> step
        }
    }.sortedBy { it.at }

    return WorkLog(
        turns = logs.flatMap { it.turns }.groupBy { it.id }.values.map { versions ->
            val samples = versions.flatMap { it.usageSamples }
                .distinctBy { it.id ?: "${it.at}:${it.input}:${it.output}:${it.cached}:${it.costUsd}" }
                .sortedBy { it.at }
            val mergedUsage = if (samples.isNotEmpty()) {
                samples.map(WorkUsageSample::toWorkUsage).reduce(WorkUsage::plus)
            } else {
                versions.mapNotNull { it.usage }.reduceOrNull(WorkUsage::plus)
            }
            versions.first().copy(
                prompt = versions.firstNotNullOfOrNull { it.prompt },
                startedAt = versions.mapNotNull { it.startedAt }.minOrNull(),
                endedAt = versions.mapNotNull { it.endedAt }.maxOrNull(),
                steps = mergeSteps(versions.flatMap { it.steps }),
                usage = mergedUsage,
                open = versions.any { it.open },
                usageSamples = samples,
            )
        }.sortedBy { it.startedAt },
        earlier = logs.any { it.earlier },
    )
}

internal fun workQuestions(input: JsonObject, id: String): List<PendingQuestion> = (input["questions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
    val question = element as? JsonObject ?: return@mapIndexedNotNull null
    fun text(key: String) = (question[key] as? JsonPrimitive)?.contentOrNull
    val title = text("question") ?: text("title") ?: return@mapIndexedNotNull null
    PendingQuestion(text("id") ?: "$id$index", title, (question["options"] as? JsonArray).orEmpty().mapNotNull { option ->
        when (option) {
            is JsonPrimitive -> option.contentOrNull
            is JsonObject -> (option["label"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    })
}
