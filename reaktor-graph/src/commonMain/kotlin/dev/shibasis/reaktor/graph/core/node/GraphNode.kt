package dev.shibasis.reaktor.graph.core.node

import kotlin.js.JsExport

@JsExport
open class GraphNode(
    val childGraph: dev.shibasis.reaktor.graph.core.Graph,
    parent: dev.shibasis.reaktor.graph.core.Graph
): Node(parent)
