@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.flow.graph.adapter

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphPalette
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.RouteNode

// References:
// - ELK/dagre-style graph layout pipelines: extract semantic grouping and packing policy before
//   rendering so visual components stay replaceable.
// - React Flow examples that precompute lanes/regions before handing nodes to the renderer.
internal fun preferredChildGraphsPerRow(childGraphCount: Int): Int = when {
    childGraphCount <= 2 -> childGraphCount
    childGraphCount <= 9 -> 3
    else -> 4
}

internal fun preferredServiceColumns(serviceCount: Int): Int = when {
    serviceCount <= 2 -> 1
    serviceCount <= 8 -> 2
    else -> 3
}

internal fun preferredRouteColumns(routeCount: Int): Int = when {
    routeCount <= 2 -> 1
    routeCount <= 16 -> 2
    routeCount <= 28 -> 3
    else -> 4
}

internal fun preferredStandaloneColumns(screenCount: Int): Int = when {
    screenCount <= 2 -> 1
    screenCount <= 12 -> 2
    screenCount <= 20 -> 3
    else -> 4
}

internal fun graphRootRoute(graph: Graph): RouteNode<*, *>? =
    (graph.backStack.entries.value.firstOrNull()?.edge?.end as? RouteNode<*, *>)
        ?: graph.nodes.filterIsInstance<RouteNode<*, *>>().firstOrNull()

internal fun regionColor(depth: Int): Color = when (depth) {
    0 -> ReaktorGraphPalette.rootRegion
    1 -> ReaktorGraphPalette.nestedRegion
    else -> ReaktorGraphPalette.deepRegion
}
