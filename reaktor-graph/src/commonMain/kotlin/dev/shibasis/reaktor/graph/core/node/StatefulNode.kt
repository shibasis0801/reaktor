package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.capabilities.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.port.ConsumerPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport

@JsExport
abstract class StatefulNode<State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    abstract val routeBinding: ConsumerPort<out RouteBinding<out Payload>>
}
