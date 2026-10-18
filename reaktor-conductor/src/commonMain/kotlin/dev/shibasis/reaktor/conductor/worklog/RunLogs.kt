package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.conductor.AgentActivityRecord
import dev.shibasis.reaktor.conductor.Author
import dev.shibasis.reaktor.conductor.EventKind
import dev.shibasis.reaktor.conductor.ThreadEvent

object RunLogs {
    private val CouncilContributions = setOf(EventKind.Proposal, EventKind.Critique, EventKind.Revision)

    fun agentName(provider: String): String = when (provider.lowercase()) {
        "codex" -> "Codex"
        "claudecode" -> "Claude"
        "chatgptgemini" -> "ChatGPT + Gemini"
        "gemini" -> "Antigravity"
        "gateway" -> "Gateway agent"
        else -> provider
    }

    fun of(events: List<ThreadEvent>, activity: List<AgentActivityRecord>, partial: Boolean, council: Boolean = false, name: (String) -> String = ::agentName): WorkLog {
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
        return WorkLog(turns.map { it.build() }, partial)
    }

    fun prompts(log: WorkLog, events: List<ThreadEvent>): WorkLog {
        val asked = events.filter { it.author is Author.Human }.map { it.text }
        if (asked.size != log.turns.size) return log
        return log.copy(turns = log.turns.zip(asked) { turn, prompt -> turn.copy(prompt = prompt) })
    }

    private class Turn(val id: String, val prompt: String?, val at: Long?) {
        val did = mutableListOf<WorkStep>()
        val contributions = mutableListOf<WorkStep>()
        val said = mutableListOf<WorkStep>()
        var ended: Long? = at

        fun build() = WorkTurn(id, prompt, at, ended, did + listOfNotNull(contributions.takeIf { it.isNotEmpty() }?.let { steps ->
            WorkStep.Subagent("$id:council", steps.first().at, "${steps.size} council contributions · proposals and cross-review", steps = steps, status = StepStatus.Done)
        }) + said)
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
            ActivityKind.Plan -> WorkStep.Tool(id, record.recordedAt, ToolKind.Other, "Plan", item.title, item.input, item.output, status)
            ActivityKind.Context -> WorkStep.Note(id, record.recordedAt, NoteKind.Compaction, item.title)
            ActivityKind.Checkpoint -> WorkStep.Note(id, record.recordedAt, NoteKind.Checkpoint, item.title)
            ActivityKind.Recovery -> WorkStep.Note(id, record.recordedAt, NoteKind.Interrupted, item.title)
            ActivityKind.Control -> WorkStep.Note(id, record.recordedAt, if (status == StepStatus.Failed) NoteKind.Error else NoteKind.Info, item.title)
        }
    }
}
