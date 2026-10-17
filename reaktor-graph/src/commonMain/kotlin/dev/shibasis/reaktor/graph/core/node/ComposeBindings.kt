package dev.shibasis.reaktor.graph.core.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.ui.ComposeContainer

@Composable
fun<P: Payload> RouteBinding<out P>.collectPayloadAsState() = payload.collectAsState()

class ComposeContainerNode(
    parent: Graph,
    graphs: ArrayList<Graph> = arrayListOf(),
    pattern: String = ""
): ContainerNode(parent, pattern, graphs), ComposeContainer {
    @Composable
    override fun Content(renderer: @Composable ((Graph, Boolean) -> Unit)) {
        val active = activeGraph ?: return
        renderer(active, true)
    }
}

