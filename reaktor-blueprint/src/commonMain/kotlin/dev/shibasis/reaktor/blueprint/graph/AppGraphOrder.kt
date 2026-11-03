package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.BlueprintGroup
import dev.shibasis.reaktor.blueprint.BlueprintNode
import dev.shibasis.reaktor.blueprint.Lanes
import dev.shibasis.reaktor.blueprint.inner
import dev.shibasis.reaktor.graph.core.Digraph

class AppGraphOrder private constructor(val digraph: Digraph, private val card: (String) -> String?) {
    private val implied = digraph.implied.toHashSet()

    fun implied(wire: Wire): Boolean {
        val from = card(wire.provider) ?: return false
        val to = card(wire.consumer) ?: return false
        return (from to to) in implied
    }

    fun columns(group: BlueprintGroup): BlueprintGroup {
        val nodes = group.nodes.map { node -> node.inner?.let { node.copy(group = columns(it)) } ?: node }
        val members = nodes.map(::leaves)
        val ranks = digraph.columns(members)
        if (nodes.all { it.inner != null } && ranks.all { it == 0 }) {
            val owner = members.flatMapIndexed { index, ids -> ids.map { it to index } }.toMap()
            val cyclic = digraph.components.any { component ->
                component.asSequence().mapNotNull(owner::get).distinct().take(2).count() > 1
            }
            if (!cyclic) return group.copy(nodes = nodes)
        }
        return group.copy(nodes = nodes.mapIndexed { index, node -> node.copy(lane = ranks[index]) }, lanes = Lanes.Ranks)
    }

    private fun leaves(node: BlueprintNode): List<String> = node.inner?.nodes?.flatMap(::leaves) ?: listOf(node.id)

    companion object {
        fun of(graph: AppGraph, after: String, card: (String) -> String?): AppGraphOrder = AppGraphOrder(
            Digraph(emptyList(), graph.wires.filter { it.consumerPort.startsWith(after) }.mapNotNull { wire ->
                val from = card(wire.provider) ?: return@mapNotNull null
                val to = card(wire.consumer) ?: return@mapNotNull null
                (from to to).takeIf { from != to }
            }),
            card,
        )

        fun nest(graph: AppGraph, groups: List<BlueprintGroup>): List<BlueprintGroup> {
            val byScope = groups.associateBy { it.key.removePrefix(FeaturePrefix) }
            fun holder(scope: String): String? = generateSequence(graph.scopes[scope]?.parent) { graph.scopes[it]?.parent }.firstOrNull { it in byScope }
            val children = byScope.keys.groupBy(::holder)
            fun build(scope: String): BlueprintGroup {
                val group = byScope.getValue(scope)
                return group.copy(nodes = group.nodes + children[scope].orEmpty().map { child -> build(child).let { BlueprintNode(it.key, 0, group = it) } })
            }
            return children[null].orEmpty().map(::build)
        }

        private const val FeaturePrefix = "feature:"
    }
}
