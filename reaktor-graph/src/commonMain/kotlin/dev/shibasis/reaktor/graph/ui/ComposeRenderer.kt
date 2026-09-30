package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.navigation.BackStackEntry
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.ui.themed
import kotlin.uuid.Uuid


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
        // Lets a theme provide its own CompositionLocals around the whole app.
        Wrap {
            Scaffold(Modifier.safeDrawingPadding()) {
                GraphContent(graph)
            }
        }
    }
}

val LocalBackStackEntry = staticCompositionLocalOf<BackStackEntry<*, *>?> { null }

@Composable
fun GraphContent(
    graph: Graph,
    isFocused: Boolean = true
) {
    val entries by graph.backStack.entries.collectAsState()
    val topEntry = entries.lastOrNull()
    val saved = rememberSaveableStateHolder()
    val provided = remember(graph) { mutableSetOf<Uuid>() }

    SideEffect {
        val live = entries.mapTo(HashSet()) { it.id }
        provided.filterNot { it in live }.forEach { saved.removeState(it.toString()) }
        provided.retainAll(live)
    }

    BackHandlerContainer(
        modifier = Modifier.fillMaxSize(),
        intercept = entries.size > 1 && isFocused,
        onBack = { graph.dispatch(Pop) }
    ) {
        if (topEntry != null) {
            val node = topEntry.edge.end.attachedNode()
            if (node == null) {
                Logger.w("GraphContent: No attached node for route '${topEntry.edge.end.id}'. Screen will be blank.")
                return@BackHandlerContainer
            }
            provided += topEntry.id
            saved.SaveableStateProvider(topEntry.id.toString()) {
                CompositionLocalProvider(LocalBackStackEntry provides topEntry) {
                    when (node) {
                        is ComposeContainer -> ContainerContent(node, isFocused)
                        is ComposeContent -> node.Content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerContent(node: ComposeContainer, isFocused: Boolean) {
    val sections = rememberSaveableStateHolder()
    val rendered = remember(node) { mutableSetOf<Uuid>() }

    node.Content { childGraph, childFocused ->
        rendered += childGraph.id
        sections.SaveableStateProvider(childGraph.id.toString()) {
            GraphContent(childGraph, childFocused && isFocused)
        }
    }

    val children = (node as? ContainerNode)?.graphs ?: return
    SideEffect {
        val alive = children.mapTo(HashSet()) { it.id }
        rendered.filterNot { it in alive }.forEach { sections.removeState(it.toString()) }
        rendered.retainAll(alive)
    }
}