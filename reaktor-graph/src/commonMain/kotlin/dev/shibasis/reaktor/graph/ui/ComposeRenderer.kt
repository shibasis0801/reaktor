package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.shibasis.reaktor.graph.core.Graph

@Composable
fun ComposeRenderer(
    graph: Graph
) {
    val activeStack by graph.activeStack.top.collectAsState()

}