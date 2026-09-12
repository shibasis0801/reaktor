package dev.shibasis.reaktor.graph.core.edge

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.NavBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import kotlin.js.JsExport


@JsExport
class NavigationEdge<P: Payload>(
    val start: RouteNode<*, *>,
    val end: RouteNode<P, *>
): Edge<NavBinding<P>>(
    start,
    start.registerConsumer<NavBinding<P>>(end.id.toString()),
    end,
    end.navBinding
) {
    val sourceGraph: Graph get() = start.graph
    val destinationGraph: Graph get() = end.graph
    val isCrossGraph: Boolean get() = sourceGraph != destinationGraph

    override fun toString(): String {
        return "[NavigationEdge] $id: ${start.pattern} -> ${end.pattern}" +
            if (isCrossGraph) " (cross-graph)" else ""
    }
}
