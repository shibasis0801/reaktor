package dev.shibasis.reaktor.conductor.workspace

data class PlanBoardCard(
    val plan: WorkPlan,
    val item: PlanItem,
    val state: PlanStatus,
    val container: Boolean,
    val path: List<PlanItem>,
    val parts: List<PlanStatus>,
    val priority: PlanPriority,
    val inherited: Boolean,
    val people: List<PlanBoardPerson>,
)

data class PlanBoardPerson(val key: String, val actor: BoardActor, val activity: String)

object PlanBoard {
    fun key(thread: String, item: String) = "$thread#$item"
    fun project(plans: List<WorkPlan>, leavesOnly: Boolean = true, selected: String? = null,
                claims: Map<String, PlanClaims> = emptyMap()): List<PlanBoardCard> = plans.flatMap { plan ->
        val keyed = plan.items.associateBy { it.id }
        val containers = plan.items.mapNotNullTo(HashSet()) { it.parent }
        val priorities = PlanAnalysis.ranked(plan).associateBy { it.item.id }
        plan.items.filter { !leavesOnly || it.id !in containers || key(plan.thread, it.id) == selected }.map { item ->
            val state = plan.state(item.id)
            val path = generateSequence(item.parent?.let(keyed::get)) { it.parent?.let(keyed::get) }.take(keyed.size).toList().asReversed()
            val assigned = generateSequence(item) { it.parent?.let(keyed::get) }.take(keyed.size)
                .map(PlanPeople::of).firstOrNull { it.isNotEmpty() }.orEmpty()
            val claim = claims[plan.thread]?.of(item)
            val holder = claim?.holder ?: item.lease?.by ?: item.by?.takeIf { state == PlanStatus.Doing }
            val named = assigned.singleOrNull { it.kind == BoardActorKind.Agent }
            val actors = listOfNotNull(holder?.let { actor ->
                if (actor.kind == BoardActorKind.Agent && named != null) actor.copy(name = named.name) else actor
            }) + assigned + listOfNotNull(item.by?.takeIf { holder == null && assigned.isEmpty() })
            val people = actors.distinctBy(PlanPeople::ref).map { actor ->
                PlanBoardPerson(PlanPeople.ref(actor), actor, when {
                    actor == actors.firstOrNull() && holder != null -> (claim?.state ?: ClaimState.Claimed).name
                    actor == item.by && assigned.isEmpty() -> ClaimState.LastChangedBy.name
                    else -> "Assigned"
                })
            }
            val candidate = priorities[item.id]
            PlanBoardCard(plan, item, state, item.id in containers, path,
                if (item.id in containers) plan.parts(item.id).map { plan.state(it.id) }.filter { it != PlanStatus.Dropped } else emptyList(),
                candidate?.priority ?: item.priority, candidate?.inherited == true, people)
        }
    }
}
