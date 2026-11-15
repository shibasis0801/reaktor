package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class TopologyNode(val key: String, val name: String)

@Serializable
data class TopologyEdge(val from: String, val to: String, val label: String = "")

@Serializable
data class Topology(
    val id: String,
    val title: String,
    val summary: String = "",
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge> = emptyList(),
) {
    fun node(key: String): TopologyNode? = nodes.firstOrNull { it.key == key }

    fun name(key: String): String = node(key)?.name ?: key

    fun into(key: String): List<TopologyEdge> = edges.filter { it.to == key }

    fun outOf(key: String): List<TopologyEdge> = edges.filter { it.from == key }

    fun edge(from: String, to: String): TopologyEdge? = edges.firstOrNull { it.from == from && it.to == to }

    fun port(edge: TopologyEdge): String {
        val base = edge.label.ifBlank { name(edge.from) }
        val clash = into(edge.to).count { it.label.ifBlank { name(it.from) } == base } > 1
        return if (clash) "$base$PortSplit${edge.from}" else base
    }

    fun duplicate(key: String, taken: Set<String> = nodes.mapTo(HashSet()) { it.key }): Topology {
        val source = node(key) ?: return this
        val copy = TopologyNode(fresh(source.key, taken), copyName(source.name))
        val wires = edges.filter { it.from == key || it.to == key }.filter { it.from != it.to }
            .map { if (it.from == key) it.copy(from = copy.key) else it.copy(to = copy.key) }
        return copy(nodes = nodes + copy, edges = edges + wires)
    }

    fun without(key: String): Topology = copy(nodes = nodes.filter { it.key != key }, edges = edges.filter { it.from != key && it.to != key })

    private fun copyName(name: String): String {
        val number = Trailing.find(name)
        val stem = number?.let { name.removeRange(it.range).trimEnd() } ?: name
        val used = nodes.mapNotNull { node -> Trailing.find(node.name)?.takeIf { node.name.removeRange(it.range).trimEnd() == stem }?.value?.trim()?.toIntOrNull() }
        return "$stem ${(used.maxOrNull() ?: 1) + 1}"
    }

    companion object {
        const val PortSplit = " · "
        private val Trailing = Regex("\\s+\\d+$")

        fun fresh(base: String, taken: Set<String>): String {
            val stem = base.replace(Regex("-\\d+$"), "").take(36).ifEmpty { "node" }
            if (stem == base && stem !in taken) return stem
            return generateSequence(2) { it + 1 }.map { "$stem-$it" }.first { it !in taken }
        }
    }
}

object Topologies {
    private fun nodes(vararg named: Pair<String, String>): List<TopologyNode> = named.map { (key, name) -> TopologyNode(key, name) }

    private fun numbered(prefix: String, title: String, count: Int): List<TopologyNode> = (1..count).map { TopologyNode("$prefix-$it", "$title $it") }

    private fun edges(vararg pairs: Pair<String, String>): List<TopologyEdge> = pairs.map { (from, to) -> TopologyEdge(from, to) }

    private val branches = numbered("branch", "Branch", 3)
    private val steps = numbered("step", "Step", 3)
    private val spokes = numbered("spoke", "Spoke", 3)

    val builtIns: List<Topology> = listOf(
        Topology("pair", "Pair", "Two nodes hand work back and forth, like a driver and a navigator.",
            nodes("a" to "A", "b" to "B"), edges("a" to "b", "b" to "a")),
        Topology("chain", "Chain", "Each node hands its output to the next, like an escalation ladder from a cheap model to a strong one.",
            steps, edges("step-1" to "step-2", "step-2" to "step-3")),
        Topology("loop", "Loop", "A chain whose last node hands back to the first, like plan, build and test.",
            steps, edges("step-1" to "step-2", "step-2" to "step-3", "step-3" to "step-1")),
        Topology("fan-out", "Fan-out", "One node splits the work between parallel nodes.",
            nodes("split" to "Split") + branches, branches.map { TopologyEdge("split", it.key) }),
        Topology("fan-in", "Fan-in", "Parallel nodes each hand their output to one node, like a council or best of N.",
            branches + nodes("join" to "Join"), branches.map { TopologyEdge(it.key, "join") }),
        Topology("split-join", "Split and join", "One node splits the work, parallel nodes do it and one node joins the results.",
            nodes("split" to "Split") + branches + nodes("join" to "Join"),
            branches.map { TopologyEdge("split", it.key) } + branches.map { TopologyEdge(it.key, "join") }),
        Topology("hub", "Hub and spokes", "One node hands out work and gets each result back, like a lead and workers.",
            nodes("hub" to "Hub") + spokes, spokes.flatMap { listOf(TopologyEdge("hub", it.key), TopologyEdge(it.key, "hub")) }),
        Topology("triangle", "Triangle", "Two nodes argue back and forth and both hand their case to a third, like a builder, a critic and a judge.",
            nodes("a" to "A", "b" to "B", "join" to "Join"), edges("a" to "b", "b" to "a", "a" to "join", "b" to "join")),
    )

    private val byId = builtIns.associateBy { it.id }

    fun builtIn(id: String): Topology? = byId[id]
}
