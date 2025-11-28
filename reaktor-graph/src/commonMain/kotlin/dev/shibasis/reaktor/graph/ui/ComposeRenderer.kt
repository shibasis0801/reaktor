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
fun Theme.Renderer(
    graph: Graph
) {
    val top by graph.activeStack.top.collectAsState()
    val destination = top?.edge?.end

    CompositionLocalProvider(LocalGraph provides graph) {
        if (destination == null) {
            TextView(text = "Null Destination")
        }
        else {
            val node = destination.attachedNode()
            if (node == null || node !is ComposeNode<*>) {
                TextView(text = "No Attached Node")
            }
            else {
                node.Content()
            }
        }
    }
}


@Composable
fun ComposeRenderer(
    graph: Graph
) = themed {
    val entries by graph.activeStack.entries.collectAsState()

    BackHandlerContainer(
        Modifier.fillMaxSize(),
        entries.size > 1,
        { graph.dispatch(Pop) }
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = text,
            shapes = shapes
        ) {
            Scaffold(Modifier.safeDrawingPadding()) {
                Renderer(graph)
            }
        }
    }
}