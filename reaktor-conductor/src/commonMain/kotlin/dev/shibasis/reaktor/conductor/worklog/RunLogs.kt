package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.json.*
import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.conductor.AgentActivityRecord
import dev.shibasis.reaktor.conductor.Author
import dev.shibasis.reaktor.conductor.EventKind
import dev.shibasis.reaktor.conductor.ThreadEvent

object RunLogs {
    private val Whitespace = Regex("\\s+")
    private val CouncilContributions = setOf(EventKind.Proposal, EventKind.Critique, EventKind.Revision)

    fun agentName(provider: String): String = when (provider.lowercase()) {
        "codex" -> "Codex"
        "claudecode" -> "Claude"
        "chatgptgemini" -> "ChatGPT + Gemini"
        "gemini" -> "Antigravity"
        "gateway" -> "Gateway agent"
        else -> provider
    }

    fun of(events: List<ThreadEvent>, activity: List<AgentActivityRecord>, partial: Boolean, council: Boolean = false, profile: Boolean = false, name: (String) -> String = ::agentName): WorkLog {
        val turns = mutableListOf<Turn>()
        events.forEach { event ->
            val at = event.createdAtEpochMillis.takeIf { it > 0 }
            when {
                event.author is Author.Human -> turns += Turn(event.id.value, event.text, at)
                council && event.kind in CouncilContributions ->
                    turns.ensure(at).contributions += WorkStep.Message(event.id.value, at, event.text, "${author(event, name)} · ${event.kind.name}")
                council && event.kind == EventKind.Synthesis ->
                    turns.ensure(at).said += WorkStep.Message(event.id.value, at, event.text, "${author(event, name)} · ${event.kind.name}")
                else -> turns.ensure(at).said += WorkStep.Message(event.id.value, at, event.text, author(event, name))
            }
        }
        latest(activity).forEach { record ->
            val turn = turns.lastOrNull { start -> start.at == null || start.at <= record.recordedAt } ?: turns.ensure(record.recordedAt)
            turn.did += step(record, name)
            if (turn.ended == null || record.recordedAt > turn.ended!!) turn.ended = record.recordedAt
        }
        return WorkLog(turns.map { it.build(profile) }, partial)
    }

    fun prompts(log: WorkLog, events: List<ThreadEvent>, profile: Boolean = false): WorkLog {
        val source = if (!profile) log else log.copy(turns = log.turns.map { turn ->
            val parsed = WorkPrompts.of(turn.context?.sent ?: turn.prompt, profile = true)
            if (parsed.text == turn.prompt) turn else turn.copy(prompt = parsed.text, context = parsed.context ?: turn.context)
        })
        val asked = events.filter { it.author is Author.Human }.map { WorkPrompts.of(it.text, profile) }
        val normalized = source.turns.map { it.prompt?.trim()?.replace(Whitespace, " ") }
        val turns = source.turns.toMutableList()
        var next = 0
        var matched = false
        var aligned = true
        val matchedTurns = mutableSetOf<Int>()
        asked.forEachIndexed { position, prompt ->
            val text = prompt.text?.trim()?.replace(Whitespace, " ")
            val index = (next until turns.size).firstOrNull { normalized[it] == text }
            if (index != null) {
                val turn = turns[index]
                turns[index] = turn.copy(prompt = prompt.text, context = turn.context ?: prompt.context)
                next = index + 1
                matched = true
                aligned = aligned && index == position
                matchedTurns += index
            }
        }
        if (asked.size == turns.size && aligned) {
            asked.forEachIndexed { index, prompt ->
                if (index !in matchedTurns && WorkPrompts.isEnvelope(turns[index].prompt)) {
                    val turn = turns[index]
                    turns[index] = turn.copy(prompt = prompt.text, context = turn.context ?: prompt.context)
                }
            }
            return source.copy(turns = turns)
        }
        return if (matched) source.copy(turns = turns) else source
    }

    private class Turn(val id: String, val prompt: String?, val at: Long?) {
        val did = mutableListOf<WorkStep>()
        val contributions = mutableListOf<WorkStep>()
        val said = mutableListOf<WorkStep>()
        var ended: Long? = at

        fun build(profile: Boolean): WorkTurn {
            val parsed = WorkPrompts.of(prompt, profile)
            return WorkTurn(id, parsed.text, at, ended, did + listOfNotNull(contributions.takeIf { it.isNotEmpty() }?.let { steps ->
                WorkStep.Subagent("$id:council", steps.first().at, "${steps.size} council contributions · proposals and cross-review", steps = steps, status = StepStatus.Done)
            }) + said, context = parsed.context)
        }
    }

    private fun MutableList<Turn>.ensure(at: Long?): Turn = lastOrNull() ?: Turn("run-start", null, at).also { add(it) }

    private fun author(event: ThreadEvent, name: (String) -> String): String = when (val author = event.author) {
        is Author.Agent -> name(author.id.value)
        is Author.Human -> "You"
        is Author.Orchestrator -> "Workspace"
    }

    private fun latest(activity: List<AgentActivityRecord>): List<AgentActivityRecord> =
        activity.groupBy { "${it.attempt}:${it.agent}:${it.item.id}" }.values.map { group ->
            group.last().let { last -> last.copy(item = last.item.copy(input = last.item.input ?: group.firstNotNullOfOrNull { it.item.input })) } to group.first().recordedAt
        }.sortedBy { it.second }.map { it.first }

    private fun step(record: AgentActivityRecord, name: (String) -> String): WorkStep {
        val item = record.item
        val id = "${record.attempt}:${record.agent}:${item.id}"
        val status = when (item.status) {
            ActivityStatus.Started, ActivityStatus.Waiting -> StepStatus.Running
            ActivityStatus.Failed -> StepStatus.Failed
            ActivityStatus.Interrupted -> StepStatus.Interrupted
            else -> StepStatus.Done
        }
        val who = name(record.agent)
        return when (item.kind) {
            ActivityKind.Tool -> WorkStep.Tool(id, record.recordedAt, ToolKind.Other, item.title, who, item.input, item.output, status, server = null, cut = item.detailTruncated)
            ActivityKind.Agent -> if (item.nativeAgents.isEmpty()) WorkStep.Note(id, record.recordedAt, NoteKind.Info, item.title)
                else WorkStep.Subagent(id, record.recordedAt, item.title, steps = item.nativeAgents.mapNotNull { agent ->
                    agent.output?.let { WorkStep.Message("$id:${agent.id}", record.recordedAt, it, agent.name ?: agent.id) }
                }, result = item.output, status = status)
            ActivityKind.Plan -> plan(id, record.recordedAt, item.input)
                ?: WorkStep.Tool(id, record.recordedAt, ToolKind.Other, "Plan", item.title, item.input, item.output, status)
            ActivityKind.Context -> WorkStep.Note(id, record.recordedAt, NoteKind.Compaction, item.title)
            ActivityKind.Checkpoint -> WorkStep.Note(id, record.recordedAt, NoteKind.Checkpoint, item.title)
            ActivityKind.Recovery -> WorkStep.Note(id, record.recordedAt, NoteKind.Interrupted, item.title)
            ActivityKind.Control -> WorkStep.Note(id, record.recordedAt, if (status == StepStatus.Failed) NoteKind.Error else NoteKind.Info, item.title)
        }
    }
    private fun plan(id: String, at: Long, input: String?): WorkStep.Plan? = runCatching {
        val value = ConductorJson.parseToJsonElement(input ?: return null).jsonObject
        val items = value["plan"]?.jsonArray?.map { entry ->
            val item = entry.jsonObject
            PlanItem(item.getValue("step").jsonPrimitive.content, when (item["status"]?.jsonPrimitive?.content) {
                "completed" -> PlanState.Done
                "in_progress" -> PlanState.Active
                else -> PlanState.Pending
            })
        } ?: return null
        WorkStep.Plan(id, at, items, value["explanation"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

}
