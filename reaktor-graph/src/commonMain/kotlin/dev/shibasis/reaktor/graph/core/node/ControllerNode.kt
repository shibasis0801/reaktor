package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.port.ConsumerPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport

@JsExport
abstract class ControllerNode<State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    abstract val routeBinding: ConsumerPort<out RouteBinding<out Payload>>

    override fun toString(): String {
        return "${super.toString()} [Controller]"
    }
}
