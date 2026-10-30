package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class PlanCandidate(
    val thread: String,
    val revision: Long,
    val item: PlanItem,
    val priority: PlanPriority,
    val inherited: Boolean,
    val unblocks: Int,
    val age: Long,
)

@Serializable
data class PlanNext(
    val candidate: PlanCandidate? = null,
    val reason: String = "",
    val quota: String = "Remaining quota is unknown",
)

object PlanSchedule {
    fun ready(plan: WorkPlan): List<PlanCandidate> {
        val items = plan.items.associateBy { it.id }
        val children = plan.items.groupBy { it.parent }
        val states = plan.items.associate { it.id to plan.state(it.id) }
        val open = plan.items.filter { states[it.id] !in setOf(PlanStatus.Done, PlanStatus.Dropped) }
        fun ancestors(item: PlanItem): List<PlanItem> =
            generateSequence(item.parent?.let(items::get)) { it.parent?.let(items::get) }.take(items.size).toList()
        val parents = open.associate { it.id to ancestors(it) }
        val dependents = mutableMapOf<String, MutableSet<String>>()
        open.forEach { item ->
            (item.after + parents[item.id].orEmpty().flatMap { it.after } + children[item.id].orEmpty().map { it.id }).distinct().forEach { dependency ->
                dependents.getOrPut(dependency) { linkedSetOf() }.add(item.id)
            }
        }
        return open.filter { item ->
            item.status == PlanStatus.Todo && item.kind != PlanKind.Component && children[item.id].isNullOrEmpty() &&
                parents[item.id].orEmpty().none { states[it.id] in setOf(PlanStatus.Done, PlanStatus.Dropped) } &&
                (item.after + parents[item.id].orEmpty().flatMap { it.after }).all { states[it] == PlanStatus.Done }
        }.map { item ->
            val followers = linkedSetOf(item.id)
            val queue = ArrayDeque(listOf(item.id))
            while (queue.isNotEmpty()) {
                dependents[queue.removeFirst()].orEmpty().forEach { if (followers.add(it)) queue.addLast(it) }
            }
            val priority = followers.mapNotNull(items::get).maxOf { it.priority }
            PlanCandidate(plan.thread, plan.revision, item, priority, priority > item.priority,
                followers.count { it != item.id && children[it].isNullOrEmpty() && items[it]?.kind != PlanKind.Component },
                item.createdAt.takeIf { it > 0 } ?: plan.createdAt)
        }.sortedWith(compareByDescending<PlanCandidate> { it.priority }.thenByDescending { it.unblocks }.thenBy { it.age })
    }
}
