package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*

internal fun projectAgentEvent(current: AgentRunRecord, event: AgentEvent, provider: RuntimeKind): AgentRunRecord {
    val participant = current.participants[event.agent.value] ?: AgentParticipantRun(provider)
    val next = when (event) {
        is AgentEvent.Started -> participant.copy(status = AgentRunStatus.Running, session = event.session, output = "", outputTruncated = false, lastTool = null)
        is AgentEvent.TurnStarted -> participant.copy(activeTurn = event.turnId)
        is AgentEvent.Delta -> participant.copy(output = (participant.output + event.text).takeLast(6000),
            outputTruncated = participant.outputTruncated || participant.output.length + event.text.length > 6000)
        is AgentEvent.ToolUse -> participant.copy(lastTool = event.tool)
        is AgentEvent.Activity -> participant.copy(lastTool = event.item.title.takeIf { event.item.kind == ActivityKind.Tool } ?: participant.lastTool)
        // Kept out of `output` so a view can collapse it, and tagged with the
        // provider's own classification so a summary is never shown as thinking.
        // A request the provider is blocked on is journalled rather than
        // answered here: policy decides, and a reconnect must not re-ask.
        is AgentEvent.RequestPending -> participant.copy(
            pending = (participant.pending.filterNot { it.id == event.request.id } + event.request).takeLast(20))
        is AgentEvent.RequestResolved -> participant.copy(
            pending = participant.pending.filterNot { it.id == event.requestId })
        is AgentEvent.Reasoning -> participant.copy(
            reasoning = (participant.reasoning + event.text).takeLast(6000),
            reasoningTruncated = participant.reasoningTruncated || participant.reasoning.length + event.text.length > 6000,
            reasoningFidelity = event.fidelity,
        )
        is AgentEvent.Finished -> participant.copy(status = if (event.outcome.interrupted) AgentRunStatus.Interrupted else if (event.outcome.ok) AgentRunStatus.Completed else AgentRunStatus.Failed,
            activeTurn = null, pending = emptyList(), effort = event.outcome.effort,
            output = (event.outcome.failure ?: event.outcome.text).takeLast(6000),
            outputTruncated = (event.outcome.failure ?: event.outcome.text).length > 6000,
            session = event.outcome.session ?: participant.session)
    }
    val withParticipant = current.copy(participants = current.participants + (event.agent.value to next))
    return if (current.collaboration != AgentCollaboration.Single) withParticipant else when (event) {
        is AgentEvent.Started -> withParticipant.copy(session = event.session)
        is AgentEvent.TurnStarted -> withParticipant
        is AgentEvent.Delta -> withParticipant.copy(output = (current.output + event.text).takeLast(12000),
            outputTruncated = current.outputTruncated || current.output.length + event.text.length > 12000)
        is AgentEvent.ToolUse -> withParticipant.copy(lastTool = event.tool)
        is AgentEvent.Activity -> withParticipant.copy(lastTool = next.lastTool)
        is AgentEvent.RequestPending -> withParticipant.copy(
            pending = (current.pending.filterNot { it.id == event.request.id } + event.request).takeLast(20))
        is AgentEvent.RequestResolved -> withParticipant.copy(
            pending = current.pending.filterNot { it.id == event.requestId })
        is AgentEvent.Reasoning -> withParticipant.copy(
            reasoning = (current.reasoning + event.text).takeLast(12000),
            reasoningTruncated = current.reasoningTruncated || current.reasoning.length + event.text.length > 12000,
            reasoningFidelity = event.fidelity)
        is AgentEvent.Finished -> withParticipant.copy(usage = event.outcome.usage, session = event.outcome.session ?: current.session,
            effort = event.outcome.effort.takeIf { it != EffortRecord.none } ?: current.effort,
            serviceTier = event.outcome.serviceTier ?: current.serviceTier)
    }
}

internal fun activityItem(event: AgentEvent): AgentActivityItem? = when (event) {
    is AgentEvent.Activity -> event.item
    is AgentEvent.Started -> AgentActivityItem("agent-stage", ActivityKind.Agent, "Agent started", ActivityStatus.Started)
    is AgentEvent.Finished -> AgentActivityItem("agent-stage", ActivityKind.Agent,
        if (event.outcome.ok) "Agent finished" else "Agent stopped", when {
            event.outcome.interrupted -> ActivityStatus.Interrupted
            event.outcome.ok -> ActivityStatus.Completed
            else -> ActivityStatus.Failed
        }, output = event.outcome.failure ?: event.outcome.text)
    is AgentEvent.RequestPending -> AgentActivityItem(event.request.id, ActivityKind.Control, event.request.title, ActivityStatus.Waiting, output = event.request.scope)
    is AgentEvent.RequestResolved -> AgentActivityItem(event.requestId, ActivityKind.Control, "Request resolved", ActivityStatus.Completed)
    else -> null
}

internal class AgentActivityProjection {
    private val stages = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun item(runId: String, event: AgentEvent): AgentActivityItem? {
        val item = activityItem(event) ?: return null
        val key = "$runId:${event.agent.value}"
        if (event is AgentEvent.Started) stages[key] = java.util.UUID.randomUUID().toString()
        val stage = stages[key] ?: "native"
        return item.copy(id = if (item.id == "agent-stage") stage else "$stage:${item.id}",
            parentId = item.parentId?.let { "$stage:$it" } ?: stage.takeUnless { item.id == "agent-stage" || it == "native" })
    }

    fun finished(runId: String) { stages.keys.removeIf { it.startsWith("$runId:") } }
}
