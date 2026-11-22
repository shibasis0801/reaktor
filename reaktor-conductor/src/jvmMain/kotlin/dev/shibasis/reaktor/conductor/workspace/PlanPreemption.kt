package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.message.MessageKind
import dev.shibasis.reaktor.conductor.message.MessageRecipient
import dev.shibasis.reaktor.conductor.message.SendMessage
import kotlinx.coroutines.runBlocking

internal fun AgentWorkspace.preemptPlans() {
    val held = plans.list().flatMap { summary -> plans.plan(summary.thread)?.let(PlanSchedule::ranked).orEmpty() }
        .filter { it.item.status == PlanStatus.Doing }
    for (current in held) {
        val actor = PlanPreemption.actor(current) ?: continue
        val next = runCatching { nextPlan(null, actor.copy(profile = null, profileRevision = null)) }.getOrNull()?.candidate ?: continue
        val choice = PlanPreemption.choose(current, next) ?: continue
        val id = digest(choice.key)
        runBlocking {
            messages.api.send(SendMessage("preempt:$id", "system:scheduler", MessageRecipient.Agent(actor.agent!!), MessageKind.Preempt,
                choice.text, item = choice.item))
        }
    }
}
