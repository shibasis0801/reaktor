package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
open class LogicNode(
    graph: Graph
): Node(graph) {
    @JsName("build")
    constructor(
        graph: Graph,
        build: (logic: LogicNode) -> Unit
    ): this(graph) {
        build(this)
    }
}




