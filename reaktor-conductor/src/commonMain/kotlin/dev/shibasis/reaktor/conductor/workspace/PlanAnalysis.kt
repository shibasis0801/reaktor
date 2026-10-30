package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class PlanTiming(
    val item: String,
    val after: List<String>,
    val earliest: Int? = null,
    val latest: Int? = null,
    val slack: Int? = null,
    val blockedBy: List<String> = emptyList(),
    val critical: Boolean = false,
)

@Serializable
data class PlanScheduleReport(
    val thread: String,
    val revision: Long,
    val unit: String = "remaining item steps; no duration estimates",
    val remaining: Int,
    val steps: Int?,
    val width: Int,
    val ready: List<PlanCandidate>,
    val criticalPath: List<String>,
    val timings: List<PlanTiming>,
    val review: List<PlanDependencyReview> = emptyList(),
)

object PlanAnalysis {
    fun report(plan: WorkPlan): PlanScheduleReport {
        val ranked = PlanSchedule.ranked(plan)
        val ids = ranked.map { it.item.id }
        val active = ids.toSet()
        val items = plan.items.associateBy { it.id }
        val dependencies = linkedMapOf<String, Set<String>>()
        val blocked = linkedMapOf<String, MutableSet<String>>()
        ranked.forEach { candidate ->
            val item = candidate.item
            val ancestors = generateSequence(item.parent?.let(items::get)) { it.parent?.let(items::get) }.take(items.size)
            val waits = linkedSetOf<String>()
            val missing = linkedSetOf<String>()
            (item.after + ancestors.flatMap { it.after }).distinct().forEach { dependency ->
                when {
                    dependency !in items -> missing.add(dependency)
                    plan.state(dependency) == PlanStatus.Done -> Unit
                    plan.state(dependency) == PlanStatus.Dropped -> missing.add(dependency)
                    else -> {
                        val leaves = plan.under(dependency).intersect(active)
                        if (leaves.isEmpty()) missing.add(dependency) else waits.addAll(leaves)
                    }
                }
            }
            dependencies[item.id] = waits
            blocked[item.id] = missing
        }
        var changed: Boolean
        do {
            changed = false
            ids.forEach { id ->
                dependencies.getValue(id).forEach { dependency ->
                    if (blocked.getValue(id).addAll(blocked.getValue(dependency))) changed = true
                }
            }
        } while (changed)
        val order = mutableListOf<String>()
        val pending = ids.filterTo(linkedSetOf()) { blocked.getValue(it).isEmpty() }
        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { dependencies.getValue(it).all(order::contains) } ?: break
            pending.remove(next)
            order.add(next)
        }
        pending.forEach { id -> blocked.getValue(id).addAll(dependencies.getValue(id).filter { it in pending }) }
        val earliest = linkedMapOf<String, Int>()
        order.forEach { id -> earliest[id] = dependencies.getValue(id).maxOfOrNull { earliest.getValue(it) + 1 } ?: 0 }
        val complete = blocked.values.all { it.isEmpty() }
        val steps = if (complete) earliest.values.maxOrNull()?.plus(1) ?: 0 else null
        val latest = linkedMapOf<String, Int>()
        if (steps != null) order.asReversed().forEach { id ->
            latest[id] = order.filter { id in dependencies.getValue(it) }.minOfOrNull { latest.getValue(it) - 1 } ?: (steps - 1)
        }
        val path = mutableListOf<String>()
        if (steps != null && steps > 0) {
            var current = order.first { earliest[it] == steps - 1 }
            path.add(current)
            while (earliest.getValue(current) > 0) {
                current = dependencies.getValue(current).first { earliest[it] == earliest.getValue(current) - 1 }
                path.add(current)
            }
            path.reverse()
        }
        val timings = ids.map { id ->
            val first = earliest[id]
            val last = latest[id]
            val slack = if (first != null && last != null) last - first else null
            PlanTiming(id, dependencies.getValue(id).toList(), first, last, slack, blocked.getValue(id).toList(), slack == 0)
        }
        val review = timings.flatMap { timing -> timing.blockedBy.map { dependency ->
            val target = items[dependency]
            val reason = when {
                target == null -> "Missing prerequisite"
                plan.state(dependency) == PlanStatus.Dropped -> target.reason.ifBlank { "Dropped prerequisite; reason was not recorded" }
                else -> "Dependency cycle or unfinished component"
            }
            PlanDependencyReview(timing.item, dependency, reason, target?.replacement)
        } }
        return PlanScheduleReport(plan.thread, plan.revision, remaining = ids.size, steps = steps,
            width = width(order, dependencies), ready = PlanSchedule.ready(plan), criticalPath = path, timings = timings, review = review)
    }

    private fun width(order: List<String>, dependencies: Map<String, Set<String>>): Int {
        val ancestors = mutableMapOf<String, Set<String>>()
        order.forEach { id -> ancestors[id] = dependencies.getValue(id).flatMapTo(linkedSetOf()) { ancestors.getValue(it) + it } }
        val matched = mutableMapOf<String, String>()
        fun augment(id: String, visited: MutableSet<String>): Boolean {
            ancestors.getValue(id).forEach { before ->
                if (visited.add(before)) {
                    val previous = matched[before]
                    if (previous == null || augment(previous, visited)) {
                        matched[before] = id
                        return true
                    }
                }
            }
            return false
        }
        return order.size - order.count { augment(it, hashSetOf()) }
    }
}
