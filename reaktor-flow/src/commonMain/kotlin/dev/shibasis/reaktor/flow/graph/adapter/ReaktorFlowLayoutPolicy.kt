package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.RouteNode

// References:
// - Graph semantics like "root route" are separate from placement strategy. The current
//   blueprint layout asks for this information, but the semantic query should stay reusable.

internal fun graphRootRoute(graph: Graph): RouteNode<*, *>? =
    (graph.backStack.entries.value.firstOrNull()?.edge?.end as? RouteNode<*, *>)
        ?: graph.nodes.filterIsInstance<RouteNode<*, *>>().firstOrNull()
