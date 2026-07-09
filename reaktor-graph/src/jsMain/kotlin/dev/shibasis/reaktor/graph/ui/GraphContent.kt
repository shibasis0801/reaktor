package dev.shibasis.reaktor.graph.ui

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.graph.core.Graph
import react.FC
import react.Props
import react.ReactNode as Component
import react.create
import kotlin.js.JsExport

external interface GraphContentProps : Props {
    var graph: Graph
    var isFocused: Boolean?
}

val GraphContentComponent: FC<GraphContentProps> = FC { props ->
    val graph = props.graph
    val focused = props.isFocused ?: true

    val (entries, _) = graph.backStack.entries.toReactState()
    val topEntry = entries.lastOrNull()

    if (topEntry != null) {
        val node = topEntry.edge.end.attachedNode()

        if (node == null) {
            Logger.w("GraphContent: No attached node for route '${topEntry.edge.end.id}'. Screen will be blank.")
            return@FC
        }

        when (node) {
            is ReactContainer -> {
                +node.Content { childGraph, childFocused ->
                    GraphContentComponent.create {
                        this.graph = childGraph
                        this.isFocused = childFocused && focused
                    }
                }
            }
            is ReactContent -> {
                +node.Content(null)
            }
        }
    }
}

@JsExport
fun ReactGraphContent(graph: Graph, isFocused: Boolean = true): Component? {
    return GraphContentComponent.create {
        this.graph = graph
        this.isFocused = isFocused
    }
}
