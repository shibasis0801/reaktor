package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

@OptIn(ExperimentalAtomicApi::class)
object PlanAnalysis {
    private data class Part(val id: String, val status: PlanStatus, val parent: String?, val after: List<String>,
        val kind: PlanKind, val priority: PlanPriority, val createdAt: Long, val reason: String, val replacement: String?)
    private data class Key(val thread: String, val revision: Long, val createdAt: Long, val items: List<Part>)
    private class Entry(val key: Key, val plan: WorkPlan) {
        val ranked by lazy { PlanSchedule.calculateRanking(plan) }
        val ready by lazy { ranked.filter { it.item.status == PlanStatus.Todo && PlanSchedule.unblocked(plan, it.item) } }
        val report by lazy { calculate(plan, ranked, ready) }
    }
    private val cache = AtomicReference<List<Entry>>(emptyList())

    private fun entry(plan: WorkPlan): Entry {
        val key = Key(plan.thread, plan.revision, plan.createdAt, plan.items.map {
            Part(it.id, it.status, it.parent, it.after.toList(), it.kind, it.priority, it.createdAt, it.reason, it.replacement)
        })
        while (true) {
            val current = cache.load()
            current.firstOrNull { it.key == key }?.let { return it }
            val added = Entry(key, plan)
            if (cache.compareAndSet(current, (current + added).takeLast(32))) return added
        }
    }

    fun ranked(plan: WorkPlan): List<PlanCandidate> {
        val found = entry(plan)
        if (found.plan.items === plan.items) return found.ranked
        val items = plan.items.associateBy { it.id }
        return found.ranked.map { it.copy(item = requireNotNull(items[it.item.id])) }
    }

    fun ready(plan: WorkPlan): List<PlanCandidate> {
        val found = entry(plan)
        if (found.plan.items === plan.items) return found.ready
        val items = plan.items.associateBy { it.id }
        return found.ready.map { it.copy(item = requireNotNull(items[it.item.id])) }
    }

    fun report(plan: WorkPlan): PlanScheduleReport {
        val found = entry(plan)
        if (found.plan.items === plan.items) return found.report
        val items = plan.items.associateBy { it.id }
        return found.report.copy(ready = found.report.ready.map { it.copy(item = requireNotNull(items[it.item.id])) })
    }

    private fun calculate(plan: WorkPlan, ranked: List<PlanCandidate>, ready: List<PlanCandidate>): PlanScheduleReport {
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
            width = width(order, dependencies), ready = ready, criticalPath = path, timings = timings, review = review)
    }

    private fun width(order: List<String>, dependencies: Map<String, Set<String>>): Int {
        val size = order.size
        val index = order.withIndex().associate { it.value to it.index }
        val words = (size + 63) / 64
        val ancestors = Array(size) { LongArray(words) }
        order.forEachIndexed { at, id ->
            val row = ancestors[at]
            dependencies.getValue(id).forEach { dependency ->
                val before = index.getValue(dependency)
                val previous = ancestors[before]
                for (word in 0 until words) row[word] = row[word] or previous[word]
                row[before ushr 6] = row[before ushr 6] or (1L shl (before and 63))
            }
        }
        val left = IntArray(size) { -1 }
        val right = IntArray(size) { -1 }
        val distance = IntArray(size)
        fun augment(at: Int): Boolean {
            val row = ancestors[at]
            row.forEachIndexed { word, initial ->
                var bits = initial
                while (bits != 0L) {
                    val before = word * 64 + bits.countTrailingZeroBits()
                    bits = bits and (bits - 1)
                    val previous = right[before]
                    if (previous == -1 || distance[previous] == distance[at] + 1 && augment(previous)) {
                        left[at] = before
                        right[before] = at
                        return true
                    }
                }
            }
            distance[at] = -1
            return false
        }
        var matched = 0
        while (true) {
            val queue = ArrayDeque<Int>()
            for (at in 0 until size) {
                distance[at] = if (left[at] == -1) 0 else -1
                if (left[at] == -1) queue.addLast(at)
            }
            var available = false
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                ancestors[at].forEachIndexed { word, initial ->
                    var bits = initial
                    while (bits != 0L) {
                        val before = word * 64 + bits.countTrailingZeroBits()
                        bits = bits and (bits - 1)
                        val previous = right[before]
                        if (previous == -1) available = true
                        else if (distance[previous] == -1) {
                            distance[previous] = distance[at] + 1
                            queue.addLast(previous)
                        }
                    }
                }
            }
            if (!available) break
            var added = 0
            for (at in 0 until size) if (left[at] == -1 && augment(at)) added++
            if (added == 0) break
            matched += added
        }
        return size - matched
    }
}
