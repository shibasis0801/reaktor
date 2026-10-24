package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.WireShape

fun GraphShape.cycles(): List<List<WireShape>> {
    val out = wires.filter { it.provider != it.consumer }.groupBy { it.provider }
    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val stack = ArrayDeque<String>()
    val open = HashSet<String>()
    val parts = ArrayList<Set<String>>()
    val work = ArrayDeque<Pair<String, Iterator<WireShape>>>()
    fun visit(id: String) {
        index[id] = index.size; low[id] = index.getValue(id)
        stack.addLast(id); open += id
        work.addLast(id to out[id].orEmpty().iterator())
    }
    for (root in nodes) {
        if (root.id in index) continue
        visit(root.id)
        while (work.isNotEmpty()) {
            val (id, edges) = work.last()
            if (edges.hasNext()) {
                val next = edges.next().consumer
                if (next !in index) visit(next) else if (next in open) low[id] = minOf(low.getValue(id), index.getValue(next))
                continue
            }
            work.removeLast()
            work.lastOrNull()?.first?.let { parent -> low[parent] = minOf(low.getValue(parent), low.getValue(id)) }
            if (low[id] != index[id]) continue
            val part = HashSet<String>()
            do { val top = stack.removeLast(); open -= top; part += top } while (top != id)
            if (part.size > 1) parts += part
        }
    }
    return parts.map { part -> loop(nodes.first { it.id in part }.id, part, out) }
}

private fun loop(start: String, part: Set<String>, out: Map<String, List<WireShape>>): List<WireShape> {
    val reached = HashMap<String, WireShape>()
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty()) {
        for (wire in out[queue.removeFirst()].orEmpty()) {
            if (wire.consumer !in part) continue
            if (wire.consumer == start) return generateSequence(wire) { reached[it.provider] }.toList().asReversed()
            if (wire.consumer !in reached) { reached[wire.consumer] = wire; queue.addLast(wire.consumer) }
        }
    }
    return emptyList()
}
