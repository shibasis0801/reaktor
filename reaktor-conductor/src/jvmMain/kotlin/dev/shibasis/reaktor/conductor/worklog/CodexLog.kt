package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.telemetry.CodexTelemetry
import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val ExploreTypes = setOf("read", "search", "list_files")

internal class CodexLog(private val root: String?) : LogParser {
    private val turns = Turns("codex-")
    private val asked = mutableListOf<Pair<Int, Int>>()
    private val telemetry = CodexTelemetry.SessionCursor()

    private var sink: ((AgentEvent) -> Unit)? = null
    private var eventAgent = AgentId("codex")
    private val live = StringBuilder()
    private var liveId: String? = null
    private var sequence = 0
    var answer: String = ""
        private set
    var boundary: String? = null
        private set
    var turnId: String? = null
        private set
    var turnStartedAt: Long? = null
        private set
    var meta: JsonObject? = null
        private set
    var context: JsonObject? = null
        private set
    val model get() = telemetry.currentModel
    val effort get() = telemetry.currentEffort

    fun events(record: JsonObject, agent: AgentId): List<AgentEvent> {
        val result = mutableListOf<AgentEvent>()
        eventAgent = agent
        sink = { result += it }
        try { line(record) } finally { sink = null }
        return result
    }

    override fun line(record: JsonObject) {
        val at = instant(record.str("timestamp"))
        when (record.str("type")) {
            "session_meta", "turn_context" -> {
                if (record.str("type") == "session_meta") meta = record.obj("payload") else context = record.obj("payload")
                telemetry.ingest(record)
            }
            "compacted" -> {
                telemetry.ingest(record)
                turns.ensure(at).add(WorkStep.Note("codex:compact:$at", at, NoteKind.Compaction, "Context compacted"))
            }
            "event_msg" -> record.obj("payload")?.let { event(record, it, at) }
            "response_item" -> record.obj("payload")?.let { response(it, at) }
        }
    }

    override fun log(earlier: Boolean) = turns.build(earlier)

    private fun event(record: JsonObject, payload: JsonObject, at: Long?) {
        when (payload.str("type")) {
            "item_started", "item_completed" -> payload.obj("item")?.let { item(it, payload, at) }
            "agent_message_delta" -> payload.str("delta")?.let { delta ->
                val turn = turns.ensure(at)
                if (liveId == null) liveId = "codex:stream:${sequence++}"
                live.append(delta)
                record(turn, WorkStep.Message(liveId!!, at, live.toString()), emit = false)
                sink?.invoke(AgentEvent.Delta(eventAgent, delta))
            }
            "agent_reasoning_delta", "reasoning_summary_text_delta" -> payload.str("delta")?.let {
                sink?.invoke(AgentEvent.Reasoning(eventAgent, it, ReasoningFidelity.Summary))
            }
            "task_started" -> {
                boundary = "task_started"
                turnId = payload.str("turn_id")
                turnStartedAt = at
                answer = ""
                live.clear()
                liveId = null
                turns.current?.open = true
            }
            "task_complete" -> {
                boundary = "task_complete"
                payload.str("last_agent_message")?.let { answer = it }
                turns.current?.let { turn ->
                turn.touch(payload.num("completed_at")?.let { if (it < 100_000_000_000) it * 1000 else it } ?: at)
                turn.open = false
                answered(at)
                }
            }
            "turn_aborted" -> {
                boundary = "turn_aborted"
                turns.current?.let { turn ->
                record(turn, WorkStep.Note("${turn.id}:aborted:${turn.steps.size}", at, NoteKind.Interrupted, "Turn interrupted"))
                turn.open = false
                }
            }
            "context_compacted" -> {
                telemetry.ingest(record)
                turns.ensure(at).add(WorkStep.Note("codex:compact:$at", at, NoteKind.Compaction, "Context compacted"))
            }
            "token_count" -> telemetry.ingest(record)?.record?.normalize()?.let { sample ->
                if (sample.hasTokens || sample.costUsd != null) {
                    turns.current?.sample(sample.toWorkUsageSample())
                }
            }
            "exec_approval_request", "apply_patch_approval_request", "request_user_input" -> {
                val turn = turns.ensure(at)
                val id = payload.str("id") ?: payload.str("call_id") ?: "${turn.id}:ask:${turn.steps.size}"
                val questions = workQuestions(payload, id)
                val text = questions.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.title } ?: payload.list("command").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.lastOrNull()
                    ?: payload.str("reason") ?: payload.str("prompt") ?: "Codex is waiting for an approval"
                asked += turns.lastIndex to record(turn, WorkStep.Question(id, at, text, questions = questions))
            }
        }
    }

    private fun response(payload: JsonObject, at: Long?) {
        if (payload.str("type") != "function_call" || payload.str("name") != "update_plan") return
        val arguments = payload.str("arguments")?.let(::parseRecord) ?: return
        val turn = turns.ensure(at)
        record(turn, WorkStep.Plan(payload.str("call_id") ?: "${turn.id}:plan:${turn.steps.size}", at, Checklist.codex(arguments), arguments.str("explanation")))
    }

    private fun item(item: JsonObject, payload: JsonObject, at: Long?) {
        val started = payload.num("started_at_ms")
        val millis = started?.let { start -> payload.num("completed_at_ms")?.minus(start) } ?: item.obj("duration")?.let { duration ->
            (duration.num("secs") ?: 0) * 1000 + (duration.num("nanos") ?: 0) / 1_000_000
        }
        val stamp = started ?: at
        val id = item.str("id") ?: "${turns.lastIndex}:${item.str("type")}:$at"
        if (item.str("type") in setOf("UserMessage", "userMessage")) {
            turns.start(text(item), stamp, item["content"].plain())
            return
        }
        val turn = turns.ensure(stamp)
        turn.touch(at)
        answered(at)
        val running = payload.str("type") == "item_started"
        val failed = item.str("status") in listOf("failed", "declined", "errored")
        when (item.str("type")) {
            "AgentMessage", "agentMessage" -> (text(item) ?: item.str("text"))?.let { value ->
                if (live.isEmpty()) sink?.invoke(AgentEvent.Delta(eventAgent, value))
                else if (value.startsWith(live.toString()) && value.length > live.length) sink?.invoke(AgentEvent.Delta(eventAgent, value.substring(live.length)))
                liveId?.let { prior -> turn.steps.removeAll { it.id == prior } }
                record(turn, WorkStep.Message(id, stamp, value), emit = false)
                answer = value
                live.clear()
                liveId = null
            }
            "Reasoning" -> {
                val raw = item["raw_content"].plain()?.trim()?.takeIf(String::isNotEmpty)
                val summary = item["summary_text"].plain()?.trim()?.takeIf(String::isNotEmpty)
                (raw ?: summary)?.let { record(turn, WorkStep.Reasoning(id, stamp, it, summary = raw == null, millis = millis)) }
            }
            "CommandExecution" -> {
                val exit = item.num("exit_code")?.toInt()
                val (output, cut) = LogText.cut(item.str("aggregated_output"))
                val parts = item.list("parsed_cmd").mapNotNull { it as? JsonObject }
                if (!failed && (exit ?: 0) == 0 && parts.isNotEmpty() && parts.all { it.str("type") in ExploreTypes })
                    parts.forEachIndexed { index, part -> record(turn, explore("$id:$index", stamp, part, if (index == 0) millis else null).copy(output = if (index == 0) output else null, cut = index == 0 && cut)) }
                else record(turn, WorkStep.Command(id, stamp, command(item), output = output?.takeIf(String::isNotEmpty), exitCode = exit, millis = millis, cut = cut,
                    status = if (running) StepStatus.Running else if (failed || exit != null && exit != 0) StepStatus.Failed else StepStatus.Done))
            }
            "FileChange" -> item.obj("changes")?.forEach { (path, value) ->
                val change = value as? JsonObject ?: return@forEach
                val file = LogText.relative(path, root) ?: path
                val status = if (running) StepStatus.Running else if (failed) StepStatus.Failed else StepStatus.Done
                val step = when (change.str("type")) {
                    "add" -> LogText.lines(change.str("content").orEmpty(), '+').let { (lines, cut) -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Add, listOf(DiffHunk(null, 1, lines)), status, cut, content = change.str("content")?.takeIf { it.length <= 1_000_000 }) }
                    "delete" -> LogText.lines(change.str("content").orEmpty(), '-').let { (lines, cut) -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Delete, listOf(DiffHunk(1, null, lines)), status, cut, content = change.str("content")?.takeIf { it.length <= 1_000_000 }) }
                    else -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Update, LogText.unified(change.str("unified_diff").orEmpty()), status,
                        movedTo = LogText.relative(change.str("move_path"), root))
                }
                record(turn, step)
            }
            "McpToolCall" -> {
                val result = item.obj("result")
                val (output, cut) = LogText.cut(if (result?.get("structuredContent") != null) result.toString() else result?.get("content").plain() ?: item.obj("error")?.str("message"))
                record(turn, WorkStep.Tool(id, stamp, ToolKind.Mcp, item.str("tool").orEmpty(), argumentSummary(item.obj("arguments")),
                    LogText.clip(item["arguments"]?.toString()), output, millis = millis, server = item.str("server"), cut = cut,
                    status = if (running) StepStatus.Running else if (failed || result?.flag("isError") == true) StepStatus.Failed else StepStatus.Done))
            }
            "WebSearch" -> record(turn, WorkStep.Tool(id, stamp, ToolKind.Web, "Web search", item.str("query"), status = StepStatus.Done, millis = millis))
            "Extension" -> record(turn, WorkStep.Tool(id, stamp, if (item.str("query") != null) ToolKind.Web else ToolKind.Other,
                item.str("kind")?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Extension", item.str("query"),
                output = item.list("results").mapNotNull { (it as? JsonObject)?.let { result -> listOfNotNull(result.str("title"), result.str("url")).joinToString(" · ") } }
                    .joinToString("\n").takeIf(String::isNotEmpty),
                status = StepStatus.Done, millis = millis))
            "ImageView" -> record(turn, WorkStep.Tool(id, stamp, ToolKind.Read, "View image", LogText.relative(item.str("path"), root), status = StepStatus.Done))
            "ContextCompaction" -> record(turn, WorkStep.Note(id, stamp, NoteKind.Compaction, "Context compacted"))
            "TodoList", "Plan" -> record(turn, WorkStep.Plan(id, stamp, item.list("items").mapNotNull { it as? JsonObject }.map { entry ->
                PlanItem(entry.str("text") ?: entry.str("step").orEmpty(), if (entry.flag("completed") || entry.str("status") == "completed") PlanState.Done else PlanState.Pending)
            }))
            "CollabAgentToolCall" -> record(turn, WorkStep.Subagent(id, stamp, item.str("tool")?.replace('_', ' ') ?: "Subagent", item.str("prompt"), item.str("model"),
                result = item.obj("agentsStates")?.values?.mapNotNull { (it as? JsonObject)?.str("message") }?.joinToString("\n")?.takeIf(String::isNotEmpty),
                status = if (failed) StepStatus.Failed else StepStatus.Done))
        }
    }

    private fun record(turn: TurnBuilder, step: WorkStep, emit: Boolean = true): Int {
        val prior = turn.steps.indexOfFirst { it.id == step.id }
        val index = if (prior < 0) turn.add(step) else prior.also { turn.steps[it] = step; turn.touch(step.at) }
        if (emit && step !is WorkStep.Message && step !is WorkStep.Reasoning) sink?.invoke(AgentEvent.Activity(eventAgent, step.activity()))
        if (emit && step is WorkStep.Reasoning) sink?.invoke(AgentEvent.Reasoning(eventAgent, step.text, if (step.summary) ReasoningFidelity.Summary else ReasoningFidelity.Thinking))
        return index
    }

    private fun answered(at: Long?) {
        asked.forEach { (turn, step) ->
            val question = turns.at(turn).steps[step] as? WorkStep.Question ?: return@forEach
            if (question.at == null || at == null || at > question.at) turns.at(turn).steps[step] = question.copy(status = StepStatus.Done, answer = question.answer ?: "Answered", answeredAt = at)
        }
        asked.removeAll { (turn, step) -> (turns.at(turn).steps[step] as? WorkStep.Question)?.status != StepStatus.Running }
    }

    private fun explore(id: String, at: Long?, part: JsonObject, millis: Long?): WorkStep.Tool {
        val path = LogText.relative(part.str("path"), root)
        return when (part.str("type")) {
            "read" -> WorkStep.Tool(id, at, ToolKind.Read, "Read", path ?: part.str("name"), part.str("cmd"), status = StepStatus.Done, millis = millis)
            "list_files" -> WorkStep.Tool(id, at, ToolKind.Search, "List folder", path ?: ".", part.str("cmd"), status = StepStatus.Done, millis = millis)
            else -> WorkStep.Tool(id, at, ToolKind.Search, "Search", listOfNotNull(part.str("query"), path).joinToString(" · ").ifEmpty { null }, part.str("cmd"),
                status = StepStatus.Done, millis = millis)
        }
    }

    private fun text(item: JsonObject): String? = item["content"].plain()?.trim()?.takeIf(String::isNotEmpty)

    private fun command(item: JsonObject): String =
        item.list("parsed_cmd").mapNotNull { (it as? JsonObject)?.str("cmd") }.joinToString(" && ").takeIf(String::isNotBlank)
            ?: item.list("command").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.lastOrNull().orEmpty()

    private fun argumentSummary(arguments: JsonObject?): String? =
        arguments?.values?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }?.let { LogText.oneLine(LogText.relative(it, root)) }
}
