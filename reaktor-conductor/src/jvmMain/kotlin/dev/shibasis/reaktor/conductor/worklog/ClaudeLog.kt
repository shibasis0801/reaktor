package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class ClaudeLog(private val root: String?, private val subagent: (String) -> WorkLog?) : LogParser {
    private class Pending(val turn: Int, val step: Int, val name: String, val input: JsonObject)

    private val turns = Turns("claude-")
    private val pending = mutableMapOf<String, Pending>()
    private val counted = mutableSetOf<String>()
    private val agents = mutableMapOf<String, Pair<Int, Int>>()
    private var plan = listOf<PlanItem>()

    override fun line(record: JsonObject) {
        if (record.flag("isSidechain")) return
        val at = instant(record.str("timestamp"))
        when (record.str("type")) {
            "user" -> user(record, at)
            "assistant" -> assistant(record, at)
            "system" -> system(record, at)
        }
    }

    override fun log(earlier: Boolean): WorkLog {
        agents.forEach { (call, place) ->
            val turn = turns.at(place.first)
            val step = turn.steps[place.second] as? WorkStep.Subagent ?: return@forEach
            if (step.status == StepStatus.Running) subagent(call)?.let { nested -> turn.steps[place.second] = step.copy(steps = nested.turns.flatMap(WorkTurn::steps)) }
        }
        agents.entries.removeAll { (_, place) -> (turns.at(place.first).steps[place.second] as? WorkStep.Subagent)?.status != StepStatus.Running }
        return turns.build(earlier)
    }

    private fun user(record: JsonObject, at: Long?) {
        val message = record.obj("message") ?: return
        val content = message["content"]
        if (content is JsonPrimitive) return prompt(record, content.contentOrNull, at)
        val blocks = message.list("content").mapNotNull { it as? JsonObject }
        blocks.filter { it.str("type") == "tool_result" }.forEach { result(it, record.obj("toolUseResult"), at) }
        val text = blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n").takeIf(String::isNotBlank)
        if (text != null && blocks.none { it.str("type") == "tool_result" }) prompt(record, text, at)
    }

    private fun prompt(record: JsonObject, raw: String?, at: Long?) {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || record.flag("isMeta")) return
        when {
            record.flag("isCompactSummary") -> turns.ensure(at).add(WorkStep.Note("${turns.lastIndex}:compact:$at", at, NoteKind.Compaction, "Context compacted"))
            text.startsWith("[Request interrupted") -> turns.current?.let { turn ->
                turn.add(WorkStep.Note("${turns.lastIndex}:interrupt:${turn.steps.size}", at, NoteKind.Interrupted, "Interrupted by the person"))
                turn.open = false
            }
            text.startsWith("<command-name>") -> {
                val name = Regex("<command-name>(.*?)</command-name>").find(text)?.groupValues?.get(1).orEmpty()
                val args = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim().orEmpty()
                turns.start(listOf(name, args).filter(String::isNotEmpty).joinToString(" "), at)
            }
            text.startsWith("<") || text.startsWith("Caveat:") -> Unit
            else -> turns.start(text, at)
        }
    }

    private fun assistant(record: JsonObject, at: Long?) {
        val message = record.obj("message") ?: return
        val turn = turns.ensure(at)
        val index = turns.lastIndex
        message.str("id")?.takeIf(counted::add)?.let { id ->
            message.obj("usage")?.let { usage ->
                val context = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").sumOf { usage.num(it) ?: 0 }
                turn.use(WorkUsage(context.takeIf { it > 0 }, usage.num("output_tokens"), usage.obj("output_tokens_details")?.num("thinking_tokens")))
            }
        }
        when (message.str("stop_reason")) {
            "end_turn", "stop_sequence", "max_tokens", "refusal" -> turn.open = false
            null -> Unit
            else -> turn.open = true
        }
        message.list("content").mapNotNull { it as? JsonObject }.forEachIndexed { position, block ->
            val id = "${message.str("id") ?: at}:${block.str("id") ?: position}"
            when (block.str("type")) {
                "text" -> block.str("text")?.trim()?.takeIf(String::isNotEmpty)?.let { turn.add(WorkStep.Message(id, at, it)) }
                "thinking" -> block.str("thinking")?.trim()?.takeIf(String::isNotEmpty)?.let { turn.add(WorkStep.Reasoning(id, at, it, summary = false, millis = record.num("thinkingDurationMs"))) }
                "tool_use" -> {
                    val name = block.str("name").orEmpty()
                    val input = block.obj("input") ?: JsonObject(emptyMap())
                    val step = turn.add(call(block.str("id") ?: id, at, name, input))
                    block.str("id")?.let { call ->
                        pending[call] = Pending(index, step, name, input)
                        if (name == "Agent" || name == "Task") agents[call] = index to step
                    }
                }
            }
        }
    }

    private fun system(record: JsonObject, at: Long?) {
        when (record.str("subtype")) {
            "compact_boundary" -> turns.ensure(at).add(WorkStep.Note("compact:$at", at, NoteKind.Compaction, "Context compacted"))
            "api_error" -> turns.ensure(at).add(WorkStep.Note("error:$at", at, NoteKind.Error, LogText.oneLine(record.str("content")) ?: "The model request failed"))
        }
    }

    private fun call(id: String, at: Long?, name: String, input: JsonObject): WorkStep {
        val file = LogText.relative(input.str("file_path") ?: input.str("notebook_path"), root)
        return when (name) {
            "Bash" -> WorkStep.Command(id, at, input.str("command").orEmpty(), purpose = input.str("description"))
            "Edit" -> edit(id, at, file, EditKind.Update, listOf(LogText.replacement(input.str("old_string").orEmpty(), input.str("new_string").orEmpty())))
            "MultiEdit" -> edit(id, at, file, EditKind.Update, input.list("edits").mapNotNull { it as? JsonObject }
                .map { LogText.replacement(it.str("old_string").orEmpty(), it.str("new_string").orEmpty()) })
            "Write" -> edit(id, at, file, EditKind.Add, listOf(LogText.lines(input.str("content").orEmpty(), '+').let { (lines, cut) -> DiffHunk(null, 1, lines) to cut }))
            "NotebookEdit" -> edit(id, at, file, EditKind.Update, listOf(LogText.lines(input.str("new_source").orEmpty(), '+').let { (lines, cut) -> DiffHunk(null, null, lines) to cut }))
            "Read" -> WorkStep.Tool(id, at, ToolKind.Read, "Read", readSummary(file, input))
            "Grep" -> WorkStep.Tool(id, at, ToolKind.Search, "Search", listOfNotNull(input.str("pattern"), LogText.relative(input.str("path"), root), input.str("glob")).joinToString(" · "))
            "Glob" -> WorkStep.Tool(id, at, ToolKind.Search, "Find files", listOfNotNull(input.str("pattern"), LogText.relative(input.str("path"), root)).joinToString(" · "))
            "WebFetch" -> WorkStep.Tool(id, at, ToolKind.Web, "Fetch", input.str("url"))
            "WebSearch" -> WorkStep.Tool(id, at, ToolKind.Web, "Web search", input.str("query"))
            "TodoWrite" -> WorkStep.Plan(id, at, todos(input))
            "mcp__ccd_turn__update_plan" -> WorkStep.Plan(id, at, steer(input))
            "Agent", "Task" -> WorkStep.Subagent(id, at, input.str("description") ?: input.str("subagent_type") ?: "Subagent", input.str("prompt"), input.str("model"))
            "AskUserQuestion" -> WorkStep.Question(id, at, input.list("questions").mapNotNull { (it as? JsonObject)?.str("question") }.joinToString("\n").ifBlank { "A question for the person" })
            "ExitPlanMode" -> WorkStep.Question(id, at, input.str("plan") ?: "Approve the plan")
            else -> if (name.startsWith("mcp__")) {
                val (server, tool) = name.removePrefix("mcp__").split("__", limit = 2).let { parts -> parts.first() to parts.getOrElse(1) { parts.first() } }
                WorkStep.Tool(id, at, ToolKind.Mcp, tool, summary(input), LogText.clip(input.toString()), server = server)
            } else WorkStep.Tool(id, at, ToolKind.Other, name, summary(input), LogText.clip(input.toString()))
        }
    }

    private fun edit(id: String, at: Long?, file: String?, kind: EditKind, hunks: List<Pair<DiffHunk, Boolean>>) =
        WorkStep.Edit(id, at, file ?: "(unknown file)", kind, hunks.map { it.first }, cut = hunks.any { it.second })

    private fun readSummary(file: String?, input: JsonObject): String {
        val offset = input.num("offset")
        val limit = input.num("limit")
        val range = when {
            offset != null && limit != null -> " · lines $offset–${offset + limit - 1}"
            offset != null -> " · from line $offset"
            limit != null -> " · first $limit lines"
            else -> ""
        }
        return (file ?: "file") + range
    }

    private fun summary(input: JsonObject): String? =
        listOf("description", "command", "query", "path", "file_path", "url", "pattern", "name", "id").firstNotNullOfOrNull { input.str(it) }
            ?.let { LogText.relative(it, root) }?.let(LogText::oneLine)
            ?: input.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }?.let(LogText::oneLine)

    private fun todos(input: JsonObject): List<PlanItem> = input.list("todos").mapNotNull { it as? JsonObject }.map { todo ->
        PlanItem(todo.str("content").orEmpty(), state(todo.str("status")))
    }.also { plan = it }

    private fun steer(input: JsonObject): List<PlanItem> {
        val items = input.list("items").mapNotNull { it as? JsonObject }
        if (items.isNotEmpty()) plan = items.map { PlanItem(it.str("content").orEmpty(), state(it.str("status"))) }
        if (input.flag("advance")) {
            val active = plan.indexOfFirst { it.state == PlanState.Active }
            plan = plan.mapIndexed { index, item -> if (index == active) item.copy(state = PlanState.Done) else item }
            val next = plan.indexOfFirst { it.state == PlanState.Pending }
            if (next >= 0) plan = plan.mapIndexed { index, item -> if (index == next) item.copy(state = PlanState.Active) else item }
        }
        val done = input.list("done").mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()
        val start = (input["start"] as? JsonPrimitive)?.intOrNull
        if (done.isNotEmpty() || start != null) plan = plan.mapIndexed { index, item ->
            when (index + 1) {
                in done -> item.copy(state = PlanState.Done)
                start -> item.copy(state = PlanState.Active)
                else -> item
            }
        }
        return plan
    }

    private fun state(status: String?) = when (status) {
        "completed" -> PlanState.Done
        "in_progress" -> PlanState.Active
        else -> PlanState.Pending
    }

    private fun result(block: JsonObject, structured: JsonObject?, at: Long?) {
        val id = block.str("tool_use_id") ?: return
        val call = pending.remove(id) ?: return
        val turn = turns.at(call.turn)
        turn.touch(at)
        val failed = block.flag("is_error")
        val text = block["content"].plain()
        val step = turn.steps[call.step]
        val millis = step.at?.let { start -> at?.minus(start)?.takeIf { it >= 0 } }
        turn.steps[call.step] = when (step) {
            is WorkStep.Command -> {
                val interrupted = structured?.flag("interrupted") == true
                val output = structured?.let { listOfNotNull(it.str("stdout"), it.str("stderr")).filter(String::isNotBlank).joinToString("\n") }?.takeIf(String::isNotEmpty) ?: text
                val background = structured?.str("backgroundTaskId")?.let { "Running in the background as $it" }
                val (shown, cut) = LogText.cut(background ?: output)
                step.copy(output = shown, cut = cut, millis = millis,
                    exitCode = if (failed) Regex("Exit code (\\d+)").find(text.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1 else 0,
                    status = when { interrupted -> StepStatus.Interrupted; failed -> StepStatus.Failed; else -> StepStatus.Done })
            }
            is WorkStep.Edit -> {
                val patch = structured?.list("structuredPatch")?.mapNotNull { it as? JsonObject }?.map { hunk ->
                    DiffHunk(hunk.num("oldStart")?.toInt(), hunk.num("newStart")?.toInt(), hunk.list("lines").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(LogText.DiffLines))
                }.orEmpty()
                val kind = when (structured?.str("type")) { "create" -> EditKind.Add; "update" -> EditKind.Update; else -> step.kind }
                step.copy(hunks = patch.ifEmpty { step.hunks }, kind = kind, status = if (failed) StepStatus.Failed else StepStatus.Done)
            }
            is WorkStep.Subagent -> {
                val nested = subagent(id)?.turns?.flatMap(WorkTurn::steps) ?: step.steps
                val running = structured?.flag("isAsync") == true && structured.str("status") != "completed"
                step.copy(steps = nested, result = if (running) null else LogText.cut(text).first, model = structured?.str("resolvedModel") ?: step.model,
                    status = when { failed -> StepStatus.Failed; running -> StepStatus.Running; else -> StepStatus.Done })
            }
            is WorkStep.Question -> step.copy(answer = LogText.oneLine(text), status = if (failed) StepStatus.Failed else StepStatus.Done)
            is WorkStep.Tool -> {
                val (shown, cut) = LogText.cut(if (step.kind == ToolKind.Read && !failed) null else text)
                step.copy(output = shown, cut = cut, millis = millis, status = if (failed) StepStatus.Failed else StepStatus.Done)
            }
            else -> step
        }
    }
}
