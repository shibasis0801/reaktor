package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import kotlinx.coroutines.flow.Flow
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.graph.navigation.Payload
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

    override val kind: NodeKind get() = NodeKind.Container

    open val changes: List<Flow<*>> get() = listOf(activeGraphIndex)

    open val dormant: GraphShape? get() = null

    open fun labelOf(graph: Graph): String =
        graph.label.ifBlank { route.pattern.original.trim('/').ifBlank { this::class.simpleName ?: "Graph" } }

    open fun shows(graph: Graph): Boolean = activeGraph == graph

    fun activateGraphForRoute(route: RouteNode<*, *>): Boolean =
        graphs.firstOrNull { graph -> graph.nodes.any { it == route } }?.let(::activate) ?: false

    open fun activate(graph: Graph): Boolean {
        val index = graphs.indexOf(graph)
        if (index < 0) return false
        activeGraphIndex.value = index
        return true
    }

    override fun toString(): String {
        return "${super.toString()} [Container] children=${graphs.size} active=${activeGraphIndex.value}"
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
