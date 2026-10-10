package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape

data class Feature(val key: String, val label: String, val members: List<NodeShape>, val shared: Boolean = false)

object AppGraphFeatures {
    private const val Start = "start"
    private const val HubHops = 4

    fun of(graph: AppGraph): List<Feature> {
        val islandOf = graph.nodes.values.associate { it.id to graph.island(it.scope) }
        val app = graph.nodes.values.filter { islandOf[it.id] == null }
        val islands = graph.nodes.values.filter { islandOf[it.id] != null }.groupBy { islandOf.getValue(it.id)!! }
            .map { (scope, members) -> Feature(scope, graph.scopes[scope]?.label ?: scope, members) }
            .sortedBy { it.label }
        val entries = app.filter { it.kind == NodeKind.Screen || it.kind == NodeKind.Container }
        val hubs = entries.filter { graph.hopsFrom(it.id).size > HubHops }.map { it.id }.toSet()
        val segmentOf = entries.associate { it.id to segment(it.route) }
        val parent = HashMap<String, String>()
        fun find(key: String): String = parent[key]?.let(::find) ?: key
        fun weight(key: String): Int = segmentOf.values.count { find(it) == key }
        val order = compareByDescending<String> { weight(it) }.thenBy { it != Start }.thenBy { it }
        val sizes = segmentOf.values.groupingBy { it }.eachCount()
        entries.filter { it.id !in hubs && sizes[segmentOf.getValue(it.id)] == 1 }.sortedBy { it.route ?: it.label }.forEach { entry ->
            val own = find(segmentOf.getValue(entry.id))
            val neighbours = (graph.hopsFrom(entry.id).map { it.to } + graph.hopsTo(entry.id).map { it.from })
                .filter { it in segmentOf && it !in hubs }
                .map { find(segmentOf.getValue(it)) }
                .filter { it != own }
            val best = neighbours.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparator { a, b -> order.compare(a.key, b.key) })
                .firstOrNull()?.key ?: return@forEach
            val (keep, drop) = listOf(own, best).sortedWith(order).let { it[0] to it[1] }
            parent[drop] = keep
        }
        val featureOf = HashMap<String, String>()
        segmentOf.forEach { (id, key) -> featureOf[id] = find(key) }
        val entryIds = segmentOf.keys
        app.filter { it.id !in entryIds }.forEach { node ->
            val served = downstream(graph, node.id, entryIds).mapNotNull(featureOf::get).toSet()
            featureOf[node.id] = served.singleOrNull() ?: shared(graph.role(node))
        }
        val grouped = app.groupBy { featureOf.getValue(it.id) }
        val features = grouped.map { (key, members) ->
            val sharedRole = Role.entries.firstOrNull { shared(it) == key }
            Feature(key, sharedRole?.let { "Shared ${it.label.lowercase()}" } ?: label(key, members), members, shared = sharedRole != null)
        }
        return features.filter { !it.shared }.sortedWith(compareBy<Feature> { it.key != Start }.thenByDescending { it.members.size }.thenBy { it.label }) +
            features.filter { it.shared }.sortedBy { feature -> Role.entries.first { shared(it) == feature.key }.lane } +
            islands
    }

    private fun shared(role: Role): String = "shared-${role.name.lowercase()}"

    private fun segment(route: String?): String = route?.trim('/')?.substringBefore('/')?.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: Start

    private fun label(key: String, members: List<NodeShape>): String {
        val words = key.replace('-', ' ')
        val spelled = Regex("\\b${Regex.escape(words)}\\b", RegexOption.IGNORE_CASE)
        return (members.sortedBy { it.label }.firstNotNullOfOrNull { spelled.find(it.label)?.value } ?: words).replaceFirstChar { it.uppercase() }
    }

    private fun downstream(graph: AppGraph, start: String, entries: Set<String>): Set<String> {
        val seen = hashSetOf(start)
        val found = hashSetOf<String>()
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            graph.wiresOut(frontier.removeFirst()).forEach { wire ->
                if (!seen.add(wire.consumer)) return@forEach
                if (wire.consumer in entries) found += wire.consumer else frontier.addLast(wire.consumer)
            }
        }
        return found
    }
}
