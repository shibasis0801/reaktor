package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.port.ConsumerPort
import dev.shibasis.reaktor.graph.core.port.consumes
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.ui.themed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.js.JsExport


@JsExport
interface View {

}

interface ComposeContainer: View {
    @Composable
    fun Content(renderer: @Composable (graph: Graph, isFocused: Boolean) -> Unit)
}

// todo Make content impossible to render if there is no route binding
interface ComposeContent: View {
    @Composable
    fun Content()
}

// todo how to pass routebinding and create a consumer automatically ?
abstract class ComposeNode<State>(
    graph: Graph
): ControllerNode<State>(graph), ComposeContent {

}


abstract class StatelessComposeNode(
    graph: Graph
): ComposeNode<Unit>(graph) {
    override val state = MutableStateFlow(Unit)
}