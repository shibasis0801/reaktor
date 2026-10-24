package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node

interface GraphContentObserver {
    @Composable
    fun Content(node: Node, focused: Boolean, content: @Composable () -> Unit)
    fun onBack(graph: Graph, action: () -> Unit) = action()
}

val LocalGraphContentObserver = staticCompositionLocalOf<GraphContentObserver?> { null }
