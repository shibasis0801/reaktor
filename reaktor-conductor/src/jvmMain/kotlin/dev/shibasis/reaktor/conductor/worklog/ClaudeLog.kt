package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.telemetry.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class ClaudeLog(private val root: String?, cli: Boolean = false, private val subagent: (String) -> WorkLog?) : LogParser {
    private class Pending(val turn: Int, val step: Int, val name: String, val input: JsonObject)

    private val turns = Turns("claude-")
    private val pending = mutableMapOf<String, Pending>()
    private val telemetry = ClaudeTelemetry.SessionCursor()
    private val agents = mutableMapOf<String, Pair<Int, Int>>()
    private var plan = listOf<PlanItem>()

    data class Call(val at: Long?, val name: String, val input: JsonObject, val offset: Long)
    val calls = ArrayDeque<Call>()
    private var offset = 0L
    fun recordOffset(value: Long) {
        offset = value
        while (calls.firstOrNull()?.let { it.offset < value - 768 * 1024 } == true) calls.removeFirst()
    }
    fun header(record: JsonObject) {
        if (record.flag("isSidechain")) return
        listOf("cwd", "timestamp", "entrypoint", "version", "gitBranch").forEach { key -> record.str(key)?.let { metadata.putIfAbsent(key, it) } }
        (record.str("sessionId") ?: record.str("session_id"))?.let { metadata["sessionId"] = it }
        if (record.str("type") == "custom-title") customTitle = record.str("customTitle")
        if (firstPrompt == null && record.str("type") == "user" && !record.flag("isMeta") && !record.flag("isCompactSummary")) {
            val message = record.obj("message") ?: return
            val blocks = message.list("content").mapNotNull { it as? JsonObject }
            val text = ((message["content"] as? JsonPrimitive)?.contentOrNull ?: blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")).trim()
            if (blocks.none { it.str("type") == "tool_result" } && ordinaryPrompt(text)) firstPrompt = text
        }
    }
    private fun ordinaryPrompt(text: String) = text.isNotEmpty() && !text.startsWith("<") && !text.startsWith("Caveat:") && !text.startsWith("[Request interrupted")
    val metadata = mutableMapOf<String, String>()
    val samples = mutableListOf<TelemetrySample>()
    var firstPrompt: String? = null
        private set
    var prompts = 0L
        private set
    var model: String? = null
        private set
    var customTitle: String? = null
        private set
    var checklistAt: Long? = null
        private set
    var checks = emptyList<Pair<PlanItem, List<String>>>()
        private set
    val answer: String get() = resultText ?: turns.current?.steps.orEmpty().filterIsInstance<WorkStep.Message>().joinToString("\n\n") { it.text }
    val ended: Boolean get() = turns.current?.open == false
    val usage: AgentUsage get() = finalUsage ?: telemetry.aggregatedLiveUsage()
    private var resultText: String? = null
    private var failure: String? = null
    private var completed = false
    private var serviceTier: String? = null
    private var budgetExceeded = false
    private var usageLimit: AgentUsageLimit? = null
    private var finalUsage: AgentUsage? = null
    private val streamedText = if (cli) StringBuilder() else null
    private var emit: ((AgentEvent) -> Unit)? = null
    private var eventAgent = AgentId("claude-transcript")

    fun events(record: JsonObject, agent: AgentId = AgentId("claude-transcript")): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        eventAgent = agent
        emit = events::add
        try { line(record) } finally { emit = null }
        return events
    }

    fun finish(agent: AgentId, effort: EffortRecord, exitCode: Int, stderr: String): AgentOutcome {
        val body = resultText ?: streamedText?.toString() ?: answer
        val ok = completed && failure == null && exitCode == 0 && body.isNotBlank()
        return AgentOutcome(agent, body, ok, failure = when {
            ok -> null
            failure != null -> failure
            !completed && stderr.isBlank() -> "Claude stream ended without a result envelope"
            stderr.isNotBlank() -> stderr.trim().take(2000)
            else -> "claude exited with code $exitCode"
        }, session = metadata["sessionId"]?.let { ProviderSession(RuntimeKind.ClaudeCode, it) }, usage = finalUsage,
            effort = effort, serviceTier = serviceTier, usageLimit = usageLimit.takeUnless { ok },
            attributes = if (budgetExceeded) mapOf("budgetExceeded" to "Claude Code stopped at the native USD budget; inspect retained work before retrying") else emptyMap())
    }

    override fun line(record: JsonObject) {
        if (record.flag("isSidechain")) return
        val at = instant(record.str("timestamp"))
        header(record)
        val info = record.obj("api_error_params")?.obj("rate_limit_info")
        if (record.str("parent_tool_use_id") == null && record.str("api_error") == "usage_limit_reached")
            usageLimit = AgentUsageLimit(RuntimeKind.ClaudeCode, "usage_limit_reached", info?.num("resetsAt")?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000))
        when (record.str("type")) {
            "user" -> user(record, at)
            "assistant" -> assistant(record, at)
            "system" -> system(record, at)
            "result" -> {
                completed = true
                budgetExceeded = record.str("subtype") == "error_max_budget_usd"
                val body = record["structured_output"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.toString() ?: record.str("result")
                if (record.text("is_error") != "false" || record.str("subtype") != "success") failure = body ?: "Claude Code reported an error" else resultText = body
                val parsed = ClaudeTelemetry.parseRecord(record) as? ProviderTelemetry.Claude
                finalUsage = parsed?.toAgentUsage()
                serviceTier = parsed?.serviceTier
                turns.current?.open = false
            }
            "rate_limit_event" -> if (record.str("parent_tool_use_id") == null) {
                val rate = record.obj("rate_limit_info")
                usageLimit = if (rate?.str("status") == "rejected" && !rate.flag("isUsingOverage"))
                    AgentUsageLimit(RuntimeKind.ClaudeCode, "usage_limit_reached", rate.num("resetsAt")?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)) else null
            }
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
            else -> {
                turns.start(text, at, raw)
                if (firstPrompt == null) firstPrompt = text
                prompts++
                resultText = null
                completed = false
                failure = null
            }
        }
    }

    private fun assistant(record: JsonObject, at: Long?) {
        val message = record.obj("message") ?: return
        val turn = turns.ensure(at)
        val index = turns.lastIndex
        message.str("model")?.takeUnless { it == "<synthetic>" }?.let { model = it }
        telemetry.ingest(record)?.let { update ->
            val delta = update.delta
            if (delta.hasTokens || delta.costUsd != null || delta.compacted) samples += delta
            if (delta.hasTokens || delta.costUsd != null) {
                turn.sample(delta.toWorkUsageSample().copy(context = update.merged.contextTokens))
            }
        }
        if (message.str("id") != null && message.obj("usage") != null) emit?.invoke(AgentEvent.Usage(eventAgent, telemetry.aggregatedLiveUsage()))
        when (message.str("stop_reason")) {
            "end_turn", "stop_sequence", "max_tokens", "refusal" -> turn.open = false
            null -> Unit
            else -> turn.open = true
        }
        message.list("content").mapNotNull { it as? JsonObject }.forEachIndexed { position, block ->
            val id = "${message.str("id") ?: at}:${block.str("id") ?: position}"
            when (block.str("type")) {
                "text" -> block.str("text")?.trim()?.takeIf(String::isNotEmpty)?.let {
                    turn.add(WorkStep.Message(id, at, it))
                    val parent = record.str("parent_tool_use_id")
                    if (parent == null) streamedText?.append(block.str("text").orEmpty())
                    emit?.invoke(if (parent == null) AgentEvent.Delta(eventAgent, block.str("text").orEmpty()) else
                        AgentEvent.Activity(eventAgent, AgentActivityItem("message:$parent", ActivityKind.Agent, "Subagent output", output = it, parentId = parent)))
                }
                "thinking" -> block.str("thinking")?.trim()?.takeIf(String::isNotEmpty)?.let {
                    turn.add(WorkStep.Reasoning(id, at, it, summary = false, millis = record.num("thinkingDurationMs")))
                    emit?.invoke(AgentEvent.Reasoning(eventAgent, it, ReasoningFidelity.Thinking, record.str("parent_tool_use_id")))
                }
                "tool_use" -> {
                    val name = block.str("name").orEmpty()
                    val input = block.obj("input") ?: JsonObject(emptyMap())
                    calls.addLast(Call(at, name, input, offset))
                    if (calls.size > 40) calls.removeFirst()
                    val next = Checklist.claude(name, input, checks.map { it.first })
                    if (next != null) {
                        checks = next.map { item -> item to checks.firstOrNull { it.first.text == item.text }?.second.orEmpty() }
                        checklistAt = at
                    } else if (name in setOf("Edit", "Write", "MultiEdit", "NotebookEdit"))
                        LogText.relative(input.str("file_path") ?: input.str("notebook_path"), root)?.let { path ->
                            checks = checks.map { (item, files) -> item to if (item.state == PlanState.Active && path !in files && files.size < 20) files + path else files }
                        }
                    val callId = block.str("id") ?: id
                    val child = name == "Agent" || name == "Task"
                    val parent = record.str("parent_tool_use_id")
                    if (!child) emit?.invoke(AgentEvent.ToolUse(eventAgent, name))
                    emit?.invoke(AgentEvent.Activity(eventAgent, AgentActivityItem(callId, if (child) ActivityKind.Agent else ActivityKind.Tool, name,
                        ActivityStatus.Started, input = input.toString(), parentId = parent,
                        nativeAgents = if (child) listOf(NativeAgentState(callId, parent ?: metadata["sessionId"], input.str("description"), "running")) else emptyList())))
                    val step = turn.add(call(callId, at, name, input))
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
            "init" -> emit?.invoke(AgentEvent.Started(eventAgent, metadata["sessionId"]?.let { ProviderSession(RuntimeKind.ClaudeCode, it) }))
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
            "Write" -> edit(id, at, file, EditKind.Add, listOf(LogText.lines(input.str("content").orEmpty(), '+').let { (lines, cut) -> DiffHunk(null, 1, lines) to cut }), content = input.str("content")?.takeIf { it.length <= 1_000_000 })
            "NotebookEdit" -> edit(id, at, file, EditKind.Update, listOf(LogText.lines(input.str("new_source").orEmpty(), '+').let { (lines, cut) -> DiffHunk(null, null, lines) to cut }))
            "Read" -> WorkStep.Tool(id, at, ToolKind.Read, "Read", readSummary(file, input), input = LogText.clip(input.toString()))
            "Grep" -> WorkStep.Tool(id, at, ToolKind.Search, "Search", listOfNotNull(input.str("pattern"), LogText.relative(input.str("path"), root), input.str("glob")).joinToString(" · "), input = LogText.clip(input.toString()))
            "Glob" -> WorkStep.Tool(id, at, ToolKind.Search, "Find files", listOfNotNull(input.str("pattern"), LogText.relative(input.str("path"), root)).joinToString(" · "), input = LogText.clip(input.toString()))
            "WebFetch" -> WorkStep.Tool(id, at, ToolKind.Web, "Fetch", input.str("url"), input = LogText.clip(input.toString()))
            "WebSearch" -> WorkStep.Tool(id, at, ToolKind.Web, "Web search", input.str("query"), input = LogText.clip(input.toString()))
            in Checklist.ClaudeTools -> WorkStep.Plan(id, at, Checklist.claude(name, input, plan).orEmpty().also { plan = it })
            "Agent", "Task" -> WorkStep.Subagent(id, at, input.str("description") ?: input.str("subagent_type") ?: "Subagent", input.str("prompt"), input.str("model"))
            "AskUserQuestion" -> WorkStep.Question(id, at, input.list("questions").mapNotNull { (it as? JsonObject)?.str("question") }.joinToString("\n").ifBlank { "A question for the person" }, questions = workQuestions(input, "q"))
            "ExitPlanMode" -> WorkStep.Question(id, at, input.str("plan") ?: "Approve the plan")
            else -> if (name.startsWith("mcp__")) {
                val (server, tool) = name.removePrefix("mcp__").split("__", limit = 2).let { parts -> parts.first() to parts.getOrElse(1) { parts.first() } }
                WorkStep.Tool(id, at, ToolKind.Mcp, tool, summary(input), LogText.clip(input.toString()), server = server)
            } else WorkStep.Tool(id, at, ToolKind.Other, name, summary(input), LogText.clip(input.toString()))
        }
    }

    private fun edit(id: String, at: Long?, file: String?, kind: EditKind, hunks: List<Pair<DiffHunk, Boolean>>, content: String? = null) =
        WorkStep.Edit(id, at, file ?: "(unknown file)", kind, hunks.map { it.first }, cut = hunks.any { it.second }, content = content)

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

    private fun result(block: JsonObject, structured: JsonObject?, at: Long?) {
        val id = block.str("tool_use_id") ?: return
        val call = pending.remove(id) ?: return
        val turn = turns.at(call.turn)
        turn.touch(at)
        val failed = block.flag("is_error")
        val child = call.name == "Agent" || call.name == "Task"
        val output = block["content"]?.toString()
        emit?.invoke(AgentEvent.Activity(eventAgent, AgentActivityItem(id, if (child) ActivityKind.Agent else ActivityKind.Tool, call.name,
            if (failed) ActivityStatus.Failed else ActivityStatus.Completed, output = output,
            nativeAgents = if (child) listOf(NativeAgentState(id, metadata["sessionId"], status = if (failed) "failed" else "completed", output = output?.take(6000))) else emptyList())))
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
            is WorkStep.Question -> step.copy(answer = LogText.oneLine(text), status = if (failed) StepStatus.Failed else StepStatus.Done, answeredAt = at)
            is WorkStep.Tool -> {
                val (shown, cut) = LogText.cut(text)
                step.copy(output = shown, cut = cut, millis = millis, status = if (failed) StepStatus.Failed else StepStatus.Done)
            }
            else -> step
        }
    }
}
