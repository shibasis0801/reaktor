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
        fun identity(actor: BoardActor) = PlanIdentity.agent(actor) ?: actor.session
        val paired = plan.items.mapNotNull { item ->
            val holder = item.lease?.by ?: return@mapNotNull null
            val name = PlanPeople.statedName(holder) ?: PlanPeople.assignee(item, keyed) ?: return@mapNotNull null
            identity(holder)?.let { it to name }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (session, names) ->
            names.distinct().singleOrNull()?.let { session to it }
        }.toMap()
        plan.items.filter { !leavesOnly || it.id !in containers || key(plan.thread, it.id) == selected }.map { item ->
            val state = plan.state(item.id)
            val path = generateSequence(item.parent?.let(keyed::get)) { it.parent?.let(keyed::get) }.take(keyed.size).toList().asReversed()
            val lineage = generateSequence(item) { it.parent?.let(keyed::get) }.take(keyed.size).toList()
            val agents = lineage.map(PlanPeople::of).firstOrNull { refs -> refs.any { it.kind == BoardActorKind.Agent } }.orEmpty()
            val assigned = (agents + PlanPeople.of(item)).distinctBy(PlanPeople::ref)
            val claim = claims[plan.thread]?.of(item)
            val holder = claim?.holder ?: item.lease?.by ?: item.by?.takeIf { state == PlanStatus.Doing }
            val named = holder?.let { PlanPeople.statedName(it) ?: paired[identity(it)] }
            val actors = listOfNotNull(holder?.let { actor ->
                if (actor.kind == BoardActorKind.Agent && named != null && PlanPeople.statedName(actor) == null) actor.copy(name = named) else actor
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
