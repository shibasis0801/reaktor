package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.registerProvider
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

inline fun <reified T : Any> Graph.provide(value: T, key: String = ""): ProviderPort<T> =
    Node { graph -> object : BasicNode(graph) { override val label = T::class.simpleName.orEmpty() } }.registerProvider(key, value)
