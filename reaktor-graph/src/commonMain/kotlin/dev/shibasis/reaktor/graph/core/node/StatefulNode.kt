package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.core.port.RequirerPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport

@JsExport
abstract class StatefulNode<State>(
    graph: dev.shibasis.reaktor.graph.core.Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    abstract val routeBinding: RequirerPort<out RouteBinding<out Payload>>
}
