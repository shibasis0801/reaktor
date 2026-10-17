package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class CodexLog(private val root: String?) : LogParser {
    private val turns = Turns("codex-")
    private val asked = mutableListOf<Pair<Int, Int>>()

    override fun line(record: JsonObject) {
        val at = instant(record.str("timestamp"))
        val payload = record.obj("payload") ?: return
        when (record.str("type")) {
            "event_msg" -> event(payload, at)
            "response_item" -> response(payload, at)
        }
    }

    override fun log(earlier: Boolean) = turns.build(earlier)

    private fun event(payload: JsonObject, at: Long?) {
        when (payload.str("type")) {
            "item_completed" -> payload.obj("item")?.let { item(it, payload, at) }
            "task_started" -> turns.current?.open = true
            "task_complete" -> turns.current?.let { turn ->
                turn.touch(payload.num("completed_at")?.let { if (it < 100_000_000_000) it * 1000 else it } ?: at)
                turn.open = false
                answered(at)
            }
            "turn_aborted" -> turns.current?.let { turn ->
                turn.add(WorkStep.Note("${turn.id}:aborted:${turn.steps.size}", at, NoteKind.Interrupted, "Turn interrupted"))
                turn.open = false
            }
            "token_count" -> payload.obj("info")?.obj("last_token_usage")?.let { usage ->
                turns.current?.use(WorkUsage(usage.num("input_tokens"), usage.num("output_tokens"), usage.num("reasoning_output_tokens")))
            }
            "exec_approval_request", "apply_patch_approval_request", "request_user_input" -> {
                val turn = turns.ensure(at)
                val text = payload.list("command").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.lastOrNull()
                    ?: payload.str("reason") ?: payload.str("prompt") ?: "Codex is waiting for an approval"
                asked += turns.lastIndex to turn.add(WorkStep.Question("${turn.id}:ask:${turn.steps.size}", at, text))
            }
        }
    }

    private fun response(payload: JsonObject, at: Long?) {
        if (payload.str("type") != "function_call" || payload.str("name") != "update_plan") return
        val arguments = payload.str("arguments")?.let(::parseRecord) ?: return
        val turn = turns.ensure(at)
        turn.add(WorkStep.Plan(payload.str("call_id") ?: "${turn.id}:plan:${turn.steps.size}", at,
            arguments.list("plan").mapNotNull { it as? JsonObject }.map { step ->
                PlanItem(step.str("step").orEmpty(), when (step.str("status")) { "completed" -> PlanState.Done; "in_progress" -> PlanState.Active; else -> PlanState.Pending })
            }, arguments.str("explanation")))
    }

    private fun item(item: JsonObject, payload: JsonObject, at: Long?) {
        val started = payload.num("started_at_ms")
        val millis = started?.let { start -> payload.num("completed_at_ms")?.minus(start) } ?: item.obj("duration")?.let { duration ->
            (duration.num("secs") ?: 0) * 1000 + (duration.num("nanos") ?: 0) / 1_000_000
        }
        val stamp = started ?: at
        val id = item.str("id") ?: "${turns.lastIndex}:${item.str("type")}:$at"
        if (item.str("type") == "UserMessage") {
            turns.start(text(item), stamp)
            return
        }
        val turn = turns.ensure(stamp)
        turn.touch(at)
        answered(at)
        val failed = item.str("status") in listOf("failed", "declined", "errored")
        when (item.str("type")) {
            "AgentMessage" -> text(item)?.let { turn.add(WorkStep.Message(id, stamp, it)) }
            "Reasoning" -> {
                val raw = item["raw_content"].plain()?.trim()?.takeIf(String::isNotEmpty)
                val summary = item["summary_text"].plain()?.trim()?.takeIf(String::isNotEmpty)
                (raw ?: summary)?.let { turn.add(WorkStep.Reasoning(id, stamp, it, summary = raw == null, millis = millis)) }
            }
            "CommandExecution" -> {
                val exit = item.num("exit_code")?.toInt()
                val (output, cut) = LogText.cut(item.str("aggregated_output"))
                turn.add(WorkStep.Command(id, stamp, command(item), output = output?.takeIf(String::isNotEmpty), exitCode = exit, millis = millis, cut = cut,
                    status = if (failed || exit != null && exit != 0) StepStatus.Failed else StepStatus.Done))
            }
            "FileChange" -> item.obj("changes")?.forEach { (path, value) ->
                val change = value as? JsonObject ?: return@forEach
                val file = LogText.relative(path, root) ?: path
                val status = if (failed) StepStatus.Failed else StepStatus.Done
                val step = when (change.str("type")) {
                    "add" -> LogText.lines(change.str("content").orEmpty(), '+').let { (lines, cut) -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Add, listOf(DiffHunk(null, 1, lines)), status, cut) }
                    "delete" -> LogText.lines(change.str("content").orEmpty(), '-').let { (lines, cut) -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Delete, listOf(DiffHunk(1, null, lines)), status, cut) }
                    else -> WorkStep.Edit("$id:$file", stamp, file, EditKind.Update, LogText.unified(change.str("unified_diff").orEmpty()), status,
                        movedTo = LogText.relative(change.str("move_path"), root))
                }
                turn.add(step)
            }
            "McpToolCall" -> {
                val result = item.obj("result")
                val (output, cut) = LogText.cut(result?.get("content").plain() ?: item.obj("error")?.str("message"))
                turn.add(WorkStep.Tool(id, stamp, ToolKind.Mcp, item.str("tool").orEmpty(), argumentSummary(item.obj("arguments")),
                    LogText.clip(item["arguments"]?.toString()), output, millis = millis, server = item.str("server"), cut = cut,
                    status = if (failed || result?.flag("isError") == true) StepStatus.Failed else StepStatus.Done))
            }
            "WebSearch" -> turn.add(WorkStep.Tool(id, stamp, ToolKind.Web, "Web search", item.str("query"), status = StepStatus.Done, millis = millis))
            "Extension" -> turn.add(WorkStep.Tool(id, stamp, if (item.str("query") != null) ToolKind.Web else ToolKind.Other,
                item.str("kind")?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Extension", item.str("query"),
                output = item.list("results").mapNotNull { (it as? JsonObject)?.let { result -> listOfNotNull(result.str("title"), result.str("url")).joinToString(" · ") } }
                    .joinToString("\n").takeIf(String::isNotEmpty),
                status = StepStatus.Done, millis = millis))
            "ImageView" -> turn.add(WorkStep.Tool(id, stamp, ToolKind.Read, "View image", LogText.relative(item.str("path"), root), status = StepStatus.Done))
            "ContextCompaction" -> turn.add(WorkStep.Note(id, stamp, NoteKind.Compaction, "Context compacted"))
            "TodoList", "Plan" -> turn.add(WorkStep.Plan(id, stamp, item.list("items").mapNotNull { it as? JsonObject }.map { entry ->
                PlanItem(entry.str("text") ?: entry.str("step").orEmpty(), if (entry.flag("completed") || entry.str("status") == "completed") PlanState.Done else PlanState.Pending)
            }))
            "CollabAgentToolCall" -> turn.add(WorkStep.Subagent(id, stamp, item.str("tool")?.replace('_', ' ') ?: "Subagent", item.str("prompt"), item.str("model"),
                result = item.obj("agentsStates")?.values?.mapNotNull { (it as? JsonObject)?.str("message") }?.joinToString("\n")?.takeIf(String::isNotEmpty),
                status = if (failed) StepStatus.Failed else StepStatus.Done))
        }
    }

    private fun answered(at: Long?) {
        asked.forEach { (turn, step) ->
            val question = turns.at(turn).steps[step] as? WorkStep.Question ?: return@forEach
            if (question.at == null || at == null || at > question.at) turns.at(turn).steps[step] = question.copy(status = StepStatus.Done, answer = question.answer ?: "Answered")
        }
        asked.removeAll { (turn, step) -> (turns.at(turn).steps[step] as? WorkStep.Question)?.status != StepStatus.Running }
    }

    private fun text(item: JsonObject): String? = item["content"].plain()?.trim()?.takeIf(String::isNotEmpty)

    private fun command(item: JsonObject): String =
        item.list("parsed_cmd").mapNotNull { (it as? JsonObject)?.str("cmd") }.joinToString(" && ").takeIf(String::isNotBlank)
            ?: item.list("command").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.lastOrNull().orEmpty()

    private fun argumentSummary(arguments: JsonObject?): String? =
        arguments?.values?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }?.let { LogText.oneLine(LogText.relative(it, root)) }
}
