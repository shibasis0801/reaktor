package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import kotlin.js.JsExport

@JsExport
open class ContainerNode(
    val graphs: ArrayList<Graph> = arrayListOf(),
    parent: Graph
): Node(parent)
