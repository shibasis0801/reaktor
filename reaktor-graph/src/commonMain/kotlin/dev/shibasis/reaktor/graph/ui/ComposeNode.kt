package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.Parameters
import dev.shibasis.reaktor.graph.core.StatefulNode
import kotlin.js.JsExport


@JsExport
interface View {

}

interface ComposeContent: View {
    @Composable
    fun Content(content: @Composable () -> Unit = {})
}

abstract class ComposeNode<Props: Parameters, State>(
    graph: Graph
): StatefulNode<Props, State>(graph), ComposeContent


