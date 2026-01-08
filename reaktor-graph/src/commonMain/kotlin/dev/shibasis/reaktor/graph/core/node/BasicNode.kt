package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import kotlin.js.JsExport
import kotlin.js.JsName

// Basically a plain node, but Node is sealed, so.
@JsExport
open class BasicNode(
    graph: Graph
): Node(graph) {
    @JsName("build")
    constructor(
        graph: Graph,
        build: (logic: BasicNode) -> Unit
    ): this(graph) {
        build(this)
    }

    override fun toString(): String {
        return "${super.toString()} [Basic]"
    }
}




