package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.ui.Theme
import dev.shibasis.reaktor.ui.themed


val LocalGraph = staticCompositionLocalOf<Graph> {
    error("No Graph Provided")
}

@Composable
fun GraphApplication(
    graph: Graph
) = themed { // Your theme wrapper
    MaterialTheme(
        colorScheme = colors,
        typography = text,
        shapes = shapes
    ) {
        Scaffold(Modifier.safeDrawingPadding()) {
            GraphContent(graph)
        }
    }
}

@Composable
fun GraphContent(
    graph: Graph,
    isFocused: Boolean = true
) {
    val entries by graph.activeStack.entries.collectAsState()
    val topEntry = entries.lastOrNull()

    BackHandlerContainer(
        modifier = Modifier.fillMaxSize(),
        intercept = entries.size > 1 && isFocused,
        onBack = { graph.dispatch(Pop) }
    ) {
        if (topEntry != null) {
            val node = topEntry.edge.end.attachedNode() ?: return@BackHandlerContainer
            when (node) {
                is ComposeContainer -> node.Content { childGraph, childFocused ->
                    GraphContent(childGraph, childFocused && isFocused)
                }
                is ComposeContent -> node.Content()
                else -> {}
            }
        }
    }
}