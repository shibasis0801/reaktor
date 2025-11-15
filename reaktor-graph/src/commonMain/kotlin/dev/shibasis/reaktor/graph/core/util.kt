package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.io.network.toRoutePattern
import kotlin.js.JsExport



@JsExport
fun Graph.graph(graph: Graph) = GraphNode(graph, this)

@JsExport
fun Graph.logic(fn: Graph.(LogicNode) -> Unit) = LogicNode(this) { fn(it) }

@JsExport
fun<Props: dev.shibasis.reaktor.graph.core.Props> Graph.route(pattern: String, initialProps: Props) =
    RouteNode(this, pattern.toRoutePattern(), initialProps)

@JsExport
fun<Props: dev.shibasis.reaktor.graph.core.Props, State> Graph.node(fn: Graph.() -> StatefulNode<Props, State>) = fn()

