package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.LogicNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.node.StatefulNode
import dev.shibasis.reaktor.graph.core.port.registerProvider
import dev.shibasis.reaktor.graph.core.port.registerConsumer
import kotlin.js.JsExport



@JsExport
fun Graph.container(graphs: ArrayList<Graph>) = ContainerNode(graphs, this)

@JsExport
fun Graph.logic(fn: LogicNode.() -> Unit) = LogicNode(this) { fn(it) }

@JsExport
fun<State> Graph.stateful(fn: Graph.() -> StatefulNode<State>) = fn()

@JsExport
fun Graph.route(fn: Graph.() -> RouteNode<out Payload, out RouteBinding<out Payload>>) = fn()

fun Graph.test() {
    logic {
        var r = registerConsumer<String>()
        val t = registerProvider<() -> String> {
            ""
        }
    }
}