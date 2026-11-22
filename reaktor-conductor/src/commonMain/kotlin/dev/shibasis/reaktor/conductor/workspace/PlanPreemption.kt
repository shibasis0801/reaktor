package dev.shibasis.reaktor.conductor.workspace

data class PlanPreemptionChoice(val key: String, val text: String, val item: String)

object PlanPreemption {
    fun actor(current: PlanCandidate): BoardActor? = (current.item.lease?.by ?: current.item.by)?.takeIf { it.kind == BoardActorKind.Agent && it.agent != null }

    fun choose(current: PlanCandidate, next: PlanCandidate): PlanPreemptionChoice? {
        if (actor(current) == null || next.priority.ordinal - current.priority.ordinal < 2) return null
        return PlanPreemptionChoice(
            "${current.thread}:${current.item.id}:${current.item.revision}:${next.thread}:${next.item.id}:${next.item.revision}:${next.priority}",
            "Ready work ${next.item.id} in ${next.thread} has effective priority ${next.priority}; " +
                "your ${current.item.id} in ${current.thread} has ${current.priority}. Reach a safe checkpoint, " +
                "read the current plans, park your item as Todo with a note, then call plan.next with take=true. " +
                "Your current claim and running turn have not been changed.",
            "plan:${next.thread}#${next.item.id}",
        )
    }
}
