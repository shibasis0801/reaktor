package dev.shibasis.reaktor.graph.core.edge

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Binder
import dev.shibasis.reaktor.graph.core.node.NavBinding
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.port.PortEvent
import dev.shibasis.reaktor.graph.core.port.registerConsumer
import dev.shibasis.reaktor.graph.core.port.consumes
import dev.shibasis.reaktor.graph.ui.ComposeNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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
