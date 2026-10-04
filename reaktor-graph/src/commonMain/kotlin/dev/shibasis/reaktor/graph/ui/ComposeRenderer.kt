package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    ) { backProgress ->
        val revealing by remember(backProgress) { derivedStateOf { backProgress() > 0f } }
        val below = entries.getOrNull(entries.lastIndex - 1)?.takeIf { revealing }
        listOfNotNull(below, topEntry).forEach { entry ->
            key(entry.id) {
                val top = entry === topEntry
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (revealing) Modifier.swipedBack(top, backProgress) else Modifier)
                ) {
                    EntryContent(entry, isFocused && top, saved, provided)
                }
            }
        }
    }
}

@Composable
private fun EntryContent(
    entry: BackStackEntry<*, *>,
    isFocused: Boolean,
    saved: SaveableStateHolder,
    provided: MutableSet<Uuid>,
) {
    val node = entry.edge.end.attachedNode()
    if (node == null) {
        Logger.w("GraphContent: No attached node for route '${entry.edge.end.id}'. Screen will be blank.")
        return
    }
    provided += entry.id
    saved.SaveableStateProvider(entry.id.toString()) {
        CompositionLocalProvider(LocalBackStackEntry provides entry) {
            when (node) {
                is ComposeContainer -> ContainerContent(node, isFocused)
                is ComposeContent -> node.Content()
            }
        }
    }
}

private fun Modifier.swipedBack(top: Boolean, backProgress: () -> Float): Modifier =
    graphicsLayer {
        val progress = backProgress()
        translationX = if (top) size.width * progress else -size.width * UnderlayShift * (1f - progress)
    }.drawWithContent {
        drawContent()
        if (!top) drawRect(Color.Black, alpha = UnderlayDim * (1f - backProgress()))
    }

private const val UnderlayShift = .3f
private const val UnderlayDim = .12f

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