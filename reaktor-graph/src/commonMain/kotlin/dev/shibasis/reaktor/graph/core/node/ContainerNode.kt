package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.port.consumes
import dev.shibasis.reaktor.graph.navigation.Payload
import kotlin.js.JsExport

@JsExport
open class ContainerNode(
    val graphs: ArrayList<Graph> = arrayListOf(),
    parent: Graph
): Node(parent), Node.Routable {
    // It is the responsibility of the implementing container to cast it correctly to the actual screen.
    override val routeBinding by consumes<RouteBinding<Payload>>()
    override fun toString(): String {
        return "${super.toString()} [Container] children=${graphs.size}"
    }
}
