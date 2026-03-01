package dev.shibasis.reaktor.graph.core.node

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.attach
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.ui.ComposeContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport

@JsExport
open class ContainerNode(
    parent: Graph,
    pattern: String = "",
    val graphs: ArrayList<Graph> = arrayListOf()
): Node(parent), Node.Routable {
    override val routeBinding by consumes<RouteBinding<Payload>>()

    val route = RouteNode(parent, pattern) { RouteBinding(Payload()) }
    val activeGraphIndex = MutableStateFlow(0)

    init {
        connect(routeBinding, route.routeBinding)
    }

    val activeGraph: Graph?
        get() = graphs.getOrNull(activeGraphIndex.value)

    open fun activateGraphForRoute(route: RouteNode<*, *>): Boolean {
        val index = graphs.indexOfFirst { graph -> graph.nodes.any { it == route } }
        if (index >= 0) {
            activeGraphIndex.value = index
            return true
        }
        return false
    }

    override fun toString(): String {
        return "${super.toString()} [Container] children=${graphs.size} active=${activeGraphIndex.value}"
    }
}

class ComposeContainerNode(
    parent: Graph,
    graphs: ArrayList<Graph> = arrayListOf(),
    pattern: String = ""
): ContainerNode(parent, pattern, graphs), ComposeContainer {
    @Composable
    override fun Content(renderer: @Composable ((Graph, Boolean) -> Unit)) {
        val active = activeGraph ?: return
        renderer(active, true)
    }
}

fun<CN: ContainerNode, G: Graph> Graph.Container(
    pattern: String,
    children: ArrayList<G>,
    builder: Graph.(pattern: String, children: ArrayList<G>) -> CN,
): CN {
    val node = builder(this, pattern, children)
    attach(node)
    return node
}