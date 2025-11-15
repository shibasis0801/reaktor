package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.io.network.toRoutePattern
import kotlin.js.JsExport



@JsExport
fun Graph.graph(graph: Graph) = GraphNode(graph, this)

@JsExport
fun Graph.logic(fn: LogicNode.() -> Unit) = LogicNode(this) { fn(it) }

@JsExport
fun<State> Graph.stateful(fn: Graph.() -> StatefulNode<State>) = fn()

@JsExport
fun Graph.route(fn: Graph.() -> RouteNode<out Props>) = fn()

fun Graph.test() {
    logic {
        var r = registerRequirer<String>()
        val t = registerProvider<() -> String> {
            ""
        }
    }
}