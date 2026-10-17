package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.JsonObject

internal class AntigravityLog(private val root: String?) : LogParser {
    private class Pending(val turn: Int, val step: Int)

    private val turns = Turns("agy-")
    private val waiting = ArrayDeque<Pending>()

    override fun line(record: JsonObject) {
        val at = instant(record.str("created_at"))
        val index = record.text("step_index") ?: at.toString()
        val status = when (record.str("status")) { "ERROR" -> StepStatus.Failed; "RUNNING" -> StepStatus.Running; else -> StepStatus.Done }
        when (record.str("type")) {
            "USER_INPUT" -> request(record.str("content"))?.let { prompt ->
                waiting.clear()
                turns.start(prompt, at)
            }
            "PLANNER_RESPONSE" -> {
                val turn = turns.ensure(at)
                record.str("thinking")?.trim()?.takeIf(String::isNotEmpty)?.let { turn.add(WorkStep.Reasoning("agy:$index:thinking", at, it, summary = false)) }
                record.str("content")?.trim()?.takeIf(String::isNotEmpty)?.let { turn.add(WorkStep.Message("agy:$index:text", at, it)) }
                record.list("tool_calls").mapNotNull { it as? JsonObject }.forEachIndexed { position, call ->
                    waiting += Pending(turns.lastIndex, turn.add(call("agy:$index:$position", at, call.str("name").orEmpty(), call.obj("args") ?: JsonObject(emptyMap()))))
                }
                turn.open = waiting.isNotEmpty()
            }
            "GENERIC" -> {
                val pending = waiting.removeFirstOrNull()
                if (pending == null) {
                    record.str("content")?.trim()?.takeIf(String::isNotEmpty)?.let { turns.ensure(at).add(WorkStep.Message("agy:$index", at, it)) }
                    turns.current?.open = false
                } else result(pending, record.str("content"), status, at, "content" in record.list("truncated_fields").mapNotNull { it.plain() })
            }
            "CHECKPOINT" -> turns.ensure(at).add(WorkStep.Note("agy:$index", at, NoteKind.Checkpoint, "Checkpoint"))
            "ERROR_MESSAGE" -> turns.ensure(at).add(WorkStep.Note("agy:$index", at, NoteKind.Error, LogText.oneLine(record.str("content")) ?: "Antigravity reported an error"))
        }
    }

    override fun log(earlier: Boolean) = turns.build(earlier)

    private fun request(content: String?): String? {
        val text = content?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("<")) return text
        return Regex("<USER_REQUEST>(.*?)</USER_REQUEST>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun call(id: String, at: Long?, name: String, args: JsonObject): WorkStep {
        val purpose = args.str("toolSummary")
        val file = LogText.relative(args.str("TargetFile") ?: args.str("AbsolutePath"), root)
        return when (name) {
            "run_command" -> WorkStep.Command(id, at, args.str("CommandLine").orEmpty(), purpose = purpose)
            "replace_file_content", "multi_replace_file_content" -> {
                val chunks = args.list("ReplacementChunks").mapNotNull { it as? JsonObject }.ifEmpty { listOf(args) }
                val hunks = chunks.map { LogText.replacement(it.str("TargetContent").orEmpty(), it.str("ReplacementContent").orEmpty()) }
                WorkStep.Edit(id, at, file ?: "(unknown file)", EditKind.Update, hunks.map { it.first }, cut = hunks.any { it.second })
            }
            "write_to_file" -> LogText.lines(args.str("CodeContent").orEmpty(), '+').let { (lines, cut) ->
                WorkStep.Edit(id, at, file ?: "(unknown file)", if (args.flag("Overwrite")) EditKind.Update else EditKind.Add, listOf(DiffHunk(null, 1, lines)), cut = cut)
            }
            "view_file", "view_file_outline", "view_code_item" -> WorkStep.Tool(id, at, ToolKind.Read, "Read",
                listOfNotNull(file, args.num("StartLine")?.let { start -> "lines $start–${args.num("EndLine") ?: start}" }).joinToString(" · ").ifEmpty { purpose })
            "find_by_name", "grep_search", "list_dir", "codebase_search" -> WorkStep.Tool(id, at, ToolKind.Search, if (name == "list_dir") "List folder" else "Search",
                listOfNotNull(args.str("Pattern") ?: args.str("Query"), LogText.relative(args.str("SearchDirectory") ?: args.str("SearchPath") ?: args.str("DirectoryPath"), root)).joinToString(" · ").ifEmpty { purpose })
            "search_web", "read_url_content", "read_browser_page" -> WorkStep.Tool(id, at, ToolKind.Web, if (name == "search_web") "Web search" else "Fetch",
                args.str("query") ?: args.str("Url") ?: purpose)
            else -> if (name.startsWith("mcp_")) WorkStep.Tool(id, at, ToolKind.Mcp, name.removePrefix("mcp_"), purpose, LogText.clip(args.toString()))
            else WorkStep.Tool(id, at, ToolKind.Other, name.replace('_', ' ').replaceFirstChar(Char::uppercase), purpose, LogText.clip(args.toString()))
        }
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
            is WorkStep.Tool -> step.copy(output = if (step.kind == ToolKind.Read && status != StepStatus.Failed) null else output, cut = cut || truncated, millis = millis, status = status)
            else -> step
        }
    }
}
