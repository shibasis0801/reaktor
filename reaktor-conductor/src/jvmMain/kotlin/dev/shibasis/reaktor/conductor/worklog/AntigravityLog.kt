package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.telemetry.GeminiTelemetry
import kotlinx.serialization.json.JsonObject

internal class AntigravityLog(private val root: String?) : LogParser {
    private class Pending(val turn: Int, val step: Int)

    private val turns = Turns("agy-")
    private val waiting = ArrayDeque<Pending>()
    private val toolNames = mutableMapOf<String, String>()
    private var sink: ((AgentEvent) -> Unit)? = null
    private val agent = AgentId("gemini-transcript")
    private val telemetry = GeminiTelemetry.SessionCursor()
    val paths = linkedSetOf<String>()
    val samples get() = telemetry.records()
    private var spoke = false

    fun events(record: JsonObject): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        sink = { events += it }
        try { line(record) } finally { sink = null }
        return events
    }

    private fun add(turn: TurnBuilder, step: WorkStep): Int {
        when (step) {
            is WorkStep.Message -> {
                sink?.invoke(AgentEvent.Delta(agent, if (spoke) "\n\n${step.text}" else step.text))
                spoke = true
            }
            is WorkStep.Reasoning -> sink?.invoke(AgentEvent.Reasoning(agent, step.text, ReasoningFidelity.Thinking))
            else -> sink?.invoke(AgentEvent.Activity(agent, activity(step)))
        }
        return turn.add(step)
    }

    override fun line(record: JsonObject) {
        val at = instant(record.str("created_at"))
        val index = record.text("step_index") ?: at.toString()
        val status = when (record.str("status")) { "ERROR" -> StepStatus.Failed; "RUNNING" -> StepStatus.Running; else -> StepStatus.Done }
        when (record.str("type")) {
            "USER_INPUT" -> request(record.str("content"))?.let { prompt ->
                waiting.forEach { pending -> turns.at(pending.turn).settle(pending.step) }
                waiting.clear()
                turns.start(prompt.trim(), at, prompt)
                spoke = false
            }
            "PLANNER_RESPONSE" -> {
                val turn = turns.ensure(at)
                telemetry.ingest(record)
                GeminiTelemetry.parseRecord(record)?.normalize()?.let { sample ->
                    if (sample.hasTokens || sample.costUsd != null) {
                        turn.sample(sample.toWorkUsageSample())
                    }
                }
                record.str("thinking")?.trim()?.takeIf(String::isNotEmpty)?.let { add(turn, WorkStep.Reasoning("agy:$index:thinking", at, it, summary = false)) }
                record.str("content")?.trim()?.takeIf(String::isNotEmpty)?.let { add(turn, WorkStep.Message("agy:$index:text", at, it)) }
                record.list("tool_calls").mapNotNull { it as? JsonObject }.forEachIndexed { position, call ->
                    val id = "agy:$index:$position"
                    val name = call.str("name").orEmpty()
                    toolNames[id] = name
                    waiting += Pending(turns.lastIndex, add(turn, call(id, at, name, call.obj("args")?.parsedArguments() ?: JsonObject(emptyMap()))))
                }
                turn.open = waiting.isNotEmpty()
            }
            "GENERIC" -> {
                val pending = waiting.removeFirstOrNull()
                if (pending == null) {
                    record.str("content")?.trim()?.takeIf(String::isNotEmpty)?.let { add(turns.ensure(at), WorkStep.Message("agy:$index", at, it)) }
                    turns.current?.open = false
                } else result(pending, if (status == StepStatus.Failed) record.str("error") ?: record.str("content") else record.str("content"), status, at, "content" in record.list("truncated_fields").mapNotNull { it.plain() })
            }
            "CHECKPOINT" -> add(turns.ensure(at), WorkStep.Note("agy:$index", at, NoteKind.Checkpoint, "Checkpoint"))
            "ERROR_MESSAGE" -> {
                waiting.removeFirstOrNull()?.let { pending ->
                    result(pending, record.str("content"), StepStatus.Failed, at, "content" in record.list("truncated_fields").mapNotNull { it.plain() })
                }
                add(turns.ensure(at), WorkStep.Note("agy:$index", at, NoteKind.Error, LogText.oneLine(record.str("content")) ?: "Antigravity reported an error"))
                turns.current?.open = false
            }
            "SYSTEM_MESSAGE", "CONVERSATION_HISTORY" -> Unit
            else -> waiting.removeFirstOrNull()?.let { pending ->
                result(pending, if (status == StepStatus.Failed) record.str("error") ?: record.str("content") else record.str("content"), status, at, "content" in record.list("truncated_fields").mapNotNull { it.plain() })
            }
        }
    }

    override fun log(earlier: Boolean) = turns.build(earlier)

    private fun request(content: String?): String? {
        val text = content?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("<")) return content
        return Regex("<USER_REQUEST>(.*?)</USER_REQUEST>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trimStart()?.takeIf(String::isNotBlank)
    }

    private fun call(id: String, at: Long?, name: String, args: JsonObject): WorkStep {
        listOf("Cwd", "TargetFile", "AbsolutePath", "Path", "SearchDirectory", "SearchPath", "DirectoryPath").mapNotNullTo(paths) { args.str(it) }
        val purpose = args.str("toolSummary")
        val file = LogText.relative(args.str("TargetFile") ?: args.str("AbsolutePath") ?: args.str("Path"), root)
        return when (name) {
            "run_command" -> WorkStep.Command(id, at, args.str("CommandLine").orEmpty(), purpose = purpose)
            "replace_file_content", "multi_replace_file_content", "edit_file" -> {
                val chunks = args.list("ReplacementChunks").mapNotNull { it as? JsonObject }.ifEmpty { listOf(args) }
                val hunks = chunks.map { LogText.replacement((it.str("TargetContent") ?: it.str("Target")).orEmpty(), (it.str("ReplacementContent") ?: it.str("Replacement")).orEmpty()) }
                WorkStep.Edit(id, at, file ?: "(unknown file)", EditKind.Update, hunks.map { it.first }, cut = hunks.any { it.second })
            }
            "write_to_file", "write_file" -> LogText.lines((args.str("CodeContent") ?: args.str("Content")).orEmpty(), '+').let { (lines, cut) ->
                WorkStep.Edit(id, at, file ?: "(unknown file)", if (args.flag("Overwrite")) EditKind.Update else EditKind.Add, listOf(DiffHunk(null, 1, lines)), cut = cut)
            }
            "view_file", "view_file_outline", "view_code_item", "read_file" -> WorkStep.Tool(id, at, ToolKind.Read, "Read",
                listOfNotNull(file, args.num("StartLine")?.let { start -> "lines $start–${args.num("EndLine") ?: start}" }).joinToString(" · ").ifEmpty { purpose }, input = LogText.clip(args.toString()))
            "find_by_name", "grep_search", "list_dir", "codebase_search" -> WorkStep.Tool(id, at, ToolKind.Search, if (name == "list_dir") "List folder" else "Search",
                listOfNotNull(args.str("Pattern") ?: args.str("Query"), LogText.relative(args.str("SearchDirectory") ?: args.str("SearchPath") ?: args.str("DirectoryPath"), root)).joinToString(" · ").ifEmpty { purpose }, input = LogText.clip(args.toString()))
            "search_web", "read_url_content", "read_browser_page" -> WorkStep.Tool(id, at, ToolKind.Web, if (name == "search_web") "Web search" else "Fetch",
                args.str("query") ?: args.str("Url") ?: purpose, input = LogText.clip(args.toString()))
            "call_mcp_tool" -> WorkStep.Tool(id, at, ToolKind.Mcp, args.str("ToolName") ?: "MCP tool", purpose, LogText.clip(args["Arguments"]?.toString()), server = args.str("ServerName"))
            else -> if (name.startsWith("mcp_")) WorkStep.Tool(id, at, ToolKind.Mcp, name.removePrefix("mcp_"), purpose, LogText.clip(args.toString()))
            else WorkStep.Tool(id, at, ToolKind.Other, name.replace('_', ' ').replaceFirstChar(Char::uppercase), purpose, LogText.clip(args.toString()))
        }
    }

    private fun activity(step: WorkStep): AgentActivityItem = step.activity().let { item ->
        item.copy(id = item.id.replace(':', '-'), title = toolNames[step.id] ?: item.title,
            kind = if (step is WorkStep.Note && step.kind == NoteKind.Checkpoint) ActivityKind.Checkpoint else item.kind)
    }

    private fun result(pending: Pending, content: String?, status: StepStatus, at: Long?, truncated: Boolean) {
        val turn = turns.at(pending.turn)
        turn.touch(at)
        val step = turn.steps[pending.step]
        val millis = step.at?.let { start -> at?.minus(start)?.takeIf { it >= 0 } }
        val (output, cut) = LogText.cut(content?.takeIf(String::isNotBlank))
        turn.steps[pending.step] = when (step) {
            is WorkStep.Command -> step.copy(output = output, cut = cut || truncated, millis = millis, status = status,
                exitCode = Regex("[Ee]xit code:? (\\d+)").find(content.orEmpty())?.groupValues?.get(1)?.toIntOrNull())
            is WorkStep.Edit -> step.copy(status = status)
            is WorkStep.Tool -> step.copy(output = output, cut = cut || truncated, millis = millis, status = status)
            else -> step
        }
        sink?.invoke(AgentEvent.Activity(agent, activity(turn.steps[pending.step])))
        turn.open = waiting.any { it.turn == pending.turn }
    }
}
