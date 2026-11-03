package dev.shibasis.reaktor.graph.core

class Digraph(nodes: Iterable<String>, edges: Iterable<Pair<String, String>>) {
    val nodes: List<String>
    val edges: List<Pair<String, String>>
    val components: List<List<String>>
    val ranks: Map<String, Int>
    val reduced: List<Pair<String, String>>

    private val index = HashMap<String, Int>()
    private val component: IntArray
    private val cyclic: BooleanArray
    private val reach: Array<LongArray>
    private val words: Int

    init {
        val listed = LinkedHashSet<String>()
        nodes.forEach { listed += it }
        val pairs = edges.distinct()
        pairs.forEach { (from, to) -> listed += from; listed += to }
        this.nodes = listed.toList()
        this.edges = pairs
        this.nodes.forEachIndexed { at, id -> index[id] = at }
        val out = Array(this.nodes.size) { ArrayList<Int>() }
        pairs.forEach { (from, to) -> out[index.getValue(from)] += index.getValue(to) }
        component = strong(out.map { it.toIntArray() })
        val count = (component.maxOrNull() ?: -1) + 1
        val members = Array(count) { ArrayList<String>() }
        this.nodes.forEachIndexed { at, id -> members[component[at]] += id }
        components = members.map { it.toList() }
        cyclic = BooleanArray(count) { members[it].size > 1 }
        pairs.filter { it.first == it.second }.forEach { cyclic[component[index.getValue(it.first)]] = true }
        val next = Array(count) { LinkedHashSet<Int>() }
        pairs.forEach { (from, to) ->
            val a = component[index.getValue(from)]
            val b = component[index.getValue(to)]
            if (a != b) next[a] += b
        }
        words = (count + 63) / 64
        reach = Array(count) { LongArray(words) }
        for (c in count - 1 downTo 0) next[c].forEach { s -> reach[c].mark(s); reach[c].join(reach[s]) }
        val rank = IntArray(count)
        for (c in 0 until count) next[c].forEach { s -> rank[s] = maxOf(rank[s], rank[c] + 1) }
        ranks = this.nodes.withIndex().associate { (at, id) -> id to rank[component[at]] }
        reduced = pairs.filter { (from, to) ->
            val a = component[index.getValue(from)]
            val b = component[index.getValue(to)]
            a == b || next[a].none { it != b && reach[it].has(b) }
        }
    }

    val implied: List<Pair<String, String>> get() = edges - reduced.toSet()

    fun cycles(): List<Set<String>> = components.indices.filter { cyclic[it] }.map { components[it].toSet() }

    fun inCycle(id: String): Boolean = index[id]?.let { cyclic[component[it]] } == true

    fun below(id: String): Set<String> {
        val c = index[id]?.let(component::get) ?: return emptySet()
        return nodes.indices.filter { at -> nodes[at] != id && (component[at] == c && cyclic[c] || reach[c].has(component[at])) }.mapTo(LinkedHashSet()) { nodes[it] }
    }

    fun above(id: String): Set<String> {
        val c = index[id]?.let(component::get) ?: return emptySet()
        return nodes.indices.filter { at -> nodes[at] != id && (component[at] == c && cyclic[c] || reach[component[at]].has(c)) }.mapTo(LinkedHashSet()) { nodes[it] }
    }

    fun reaches(from: String, to: String): Boolean {
        val a = index[from]?.let(component::get) ?: return false
        val b = index[to]?.let(component::get) ?: return false
        return if (a == b) cyclic[a] else reach[a].has(b)
    }

    fun columns(groups: List<Collection<String>>): List<Int> {
        val present = groups.map { group -> LongArray(words).also { bits -> group.forEach { id -> index[id]?.let { bits.mark(component[it]) } } } }
        val reached = groups.map { group -> LongArray(words).also { bits -> group.forEach { id -> index[id]?.let { bits.join(reach[component[it]]) } } } }
        val arcs = ArrayList<Pair<String, String>>()
        groups.indices.forEach { a -> groups.indices.forEach { b -> if (a != b && reached[a].meets(present[b])) arcs += "$a" to "$b" } }
        val order = Digraph(groups.indices.map(Int::toString), arcs).ranks
        return groups.indices.map { order.getValue(it.toString()) }
    }

    private fun strong(out: List<IntArray>): IntArray {
        val count = out.size
        val found = IntArray(count) { -1 }
        val low = IntArray(count)
        val held = BooleanArray(count)
        val stack = IntArray(count)
        val calls = IntArray(count)
        val cursor = IntArray(count)
        val owner = IntArray(count) { -1 }
        var top = 0
        var depth = 0
        var seen = 0
        var made = 0
        for (root in 0 until count) {
            if (found[root] >= 0) continue
            found[root] = seen; low[root] = seen++; stack[top++] = root; held[root] = true; calls[depth++] = root
            while (depth > 0) {
                val v = calls[depth - 1]
                if (cursor[v] < out[v].size) {
                    val w = out[v][cursor[v]++]
                    if (found[w] < 0) {
                        found[w] = seen; low[w] = seen++; stack[top++] = w; held[w] = true; calls[depth++] = w
                    } else if (held[w]) low[v] = minOf(low[v], found[w])
                } else {
                    depth--
                    if (depth > 0) calls[depth - 1].let { parent -> low[parent] = minOf(low[parent], low[v]) }
                    if (low[v] == found[v]) {
                        do {
                            val w = stack[--top]
                            held[w] = false
                            owner[w] = made
                        } while (w != v)
                        made++
                    }
                }
            }
        }
        return IntArray(count) { made - 1 - owner[it] }
    }

    private fun LongArray.mark(bit: Int) { this[bit ushr 6] = this[bit ushr 6] or (1L shl (bit and 63)) }

    private fun LongArray.has(bit: Int): Boolean = this[bit ushr 6] and (1L shl (bit and 63)) != 0L

    private fun LongArray.join(other: LongArray) { for (i in indices) this[i] = this[i] or other[i] }

    private fun LongArray.meets(other: LongArray): Boolean = indices.any { this[it] and other[it] != 0L }
}
