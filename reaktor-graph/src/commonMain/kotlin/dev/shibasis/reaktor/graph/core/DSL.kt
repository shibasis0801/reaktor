package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import kotlin.js.JsExport



@JsExport
fun Graph.container(graphs: ArrayList<Graph>) = ContainerNode(graphs, this)

@JsExport
fun Graph.logic(fn: BasicNode.() -> Unit) = BasicNode(this) { fn(it) }

@JsExport
fun Graph.route(fn: Graph.() -> RouteNode<out Payload, out RouteBinding<out Payload>>) = fn()
