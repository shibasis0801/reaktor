package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.ui.themed
import kotlin.js.JsExport


@JsExport
interface View {

}

interface ComposeContainer: View {
    @Composable
    fun Content(childRenderer: @Composable (key: String) -> Unit)
}

// Make content impossible to render if there is no route binding
interface ComposeContent: View {
    @Composable
    fun Content()
}

abstract class ComposeNode<State>(
    graph: Graph
): ControllerNode<State>(graph), ComposeContent {

}


