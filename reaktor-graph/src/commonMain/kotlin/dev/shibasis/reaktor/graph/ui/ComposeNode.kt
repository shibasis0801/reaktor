package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.StatefulNode
import kotlin.js.JsExport


@JsExport
interface View {

}

interface ComposeContainer: View {
    @Composable
    fun Content(childRenderer: @Composable (key: String) -> Unit)
}

interface ComposeContent: View {
    @Composable
    fun Content()
}

abstract class ComposeNode<State>(
    graph: Graph
): StatefulNode<State>(graph), ComposeContent {

}


