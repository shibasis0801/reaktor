package dev.shibasis.reaktor.conductor.workspace

fun linkedPlan(session: String, plans: Collection<WorkPlan>): WorkPlan? {
    val held = plans.mapNotNull { plan ->
        plan.items.filter { it.holder?.session == session }.maxOfOrNull { it.changedAt }?.let { plan to it }
    }.maxByOrNull { it.second }?.first
    if (held != null) return held
    val seated = plans.mapNotNull { plan ->
        plan.seats.filter { it.retired == null && it.session == session }.maxOfOrNull { it.at }?.let { plan to it }
    }.maxByOrNull { it.second }?.first
    if (seated != null) return seated
    return plans.mapNotNull { plan ->
        (plan.changes.filter { it.by.session == session }.map { it.at } +
            listOfNotNull(plan.createdAt.takeIf { plan.createdBy.session == session }))
            .maxOrNull()?.let { plan to it }
    }.maxByOrNull { it.second }?.first
}
