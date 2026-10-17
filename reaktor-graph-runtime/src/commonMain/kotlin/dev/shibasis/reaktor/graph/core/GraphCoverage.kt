package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.Unique

data class RouteCoverage(
    val pattern: String,
    val screens: Int,
    val exits: List<String>,
    val reachable: Boolean,
)

data class GraphCoverage(
    val graph: String,
    val routes: List<RouteCoverage>,
    val unwired: List<String>,
) {
    val screened: Int get() = routes.count { it.screens == 1 }
    val reachable: Int get() = routes.count { it.reachable }
    val edges: Int get() = routes.sumOf { it.exits.size }
    val gaps: List<String>
        get() = routes.filter { it.screens != 1 || !it.reachable }.map { route ->
            "${route.pattern.ifEmpty { "(root)" }}: ${route.screens} screens" + if (route.reachable) "" else ", unreachable from the root"
        } + unwired
    val isComplete: Boolean get() = gaps.isEmpty()

    fun describe(): String =
        "$graph: ${routes.size} routes, $screened with one screen, $reachable reachable from the root, $edges edges, ${unwired.size} unwired ports"
}

fun Graph.coverage(root: RouteNode<*, *> = backStack.entries.value.firstOrNull()?.edge?.end ?: sentinel): GraphCoverage {
    val routes = (nodes.filterIsInstance<RouteNode<*, *>>().filter { it != sentinel } + nodes.filterIsInstance<ContainerNode>().map { it.route }).distinct()
    val reached = mutableSetOf<RouteNode<*, *>>()
    val frontier = ArrayDeque(listOf<RouteNode<*, *>>(root))
    while (frontier.isNotEmpty()) {
        val route = frontier.removeFirst()
        if (reached.add(route)) frontier += route.navigationTargets()
    }
    return GraphCoverage(
        graph = label,
        routes = routes.map { route ->
            RouteCoverage(
                pattern = route.pattern.original,
                screens = route.attachedNodes().size,
                exits = route.navigationTargets().map { it.pattern.original },
                reachable = route in reached,
            )
        },
        unwired = unconnectedConsumers().map { port -> "${(port.owner as? Unique)?.label ?: port.owner}.${port.key.key}: ${port.type.type}" },
    )
}
