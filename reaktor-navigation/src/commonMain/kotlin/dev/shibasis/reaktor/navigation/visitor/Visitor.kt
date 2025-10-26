package dev.shibasis.reaktor.navigation.visitor

import dev.shibasis.reaktor.navigation.graph.*

open class Visitor {
    open fun visit(graph: Graph) {}

    open fun visit(graphNode: GraphNode) {}

    open fun visit(logicNode: LogicNode) {}
    open fun visit(routeNode: RouteNode<*>) {}
    open fun visit(viewNode: ViewNode<*, *>) {}

    open fun visit(edge: Edge<*>) {}
}

