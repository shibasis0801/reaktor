package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.message.MessageKind
import dev.shibasis.reaktor.conductor.message.MessageRecipient
import dev.shibasis.reaktor.conductor.message.SendMessage
import kotlinx.coroutines.runBlocking

internal fun AgentWorkspace.preemptPlans() {
    val held = plans.list().flatMap { summary -> plans.plan(summary.thread)?.let(PlanSchedule::ranked).orEmpty() }
        .filter { it.item.status == PlanStatus.Doing }
    for (current in held) {
        val actor = current.item.lease?.by ?: current.item.by ?: continue
        if (actor.kind != BoardActorKind.Agent || actor.agent == null) continue
        val next = runCatching { nextPlan(null, actor.copy(profile = null, profileRevision = null)) }.getOrNull()?.candidate ?: continue
        if (next.priority.ordinal - current.priority.ordinal < 2) continue
        val id = digest("${current.thread}:${current.item.id}:${current.item.revision}:${next.thread}:${next.item.id}:${next.item.revision}:${next.priority}")
        runBlocking {
            messages.api.send(SendMessage("preempt:$id", "system:scheduler", MessageRecipient.Agent(actor.agent), MessageKind.Preempt,
                "Ready work ${next.item.id} in ${next.thread} has effective priority ${next.priority}; " +
                    "your ${current.item.id} in ${current.thread} has ${current.priority}. Reach a safe checkpoint, " +
                    "read the current plans, park your item as Todo with a note, then call plan.next with take=true. " +
                    "Your current claim and running turn have not been changed.", item = "plan:${next.thread}#${next.item.id}"))
        }
    }
}
