package dev.shibasis.reaktor.portgraph.graph

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
open class PortGraph<Self: PortGraph<Self, N>, N: PortNode<Self>>(
    override val id: Uuid = Uuid.random(),
    override val label: String = ""
): Unique, Visitable {
    val nodes = arrayListOf<N>()

    open fun attach(node: N): Boolean {
        if (nodes.any { it.id == node.id }) {
            return false
        }
        nodes += node
        return true
    }

    open fun detach(node: N): Boolean {
        return nodes.remove(node)
    }

    open fun close() {
        nodes.toList().forEach {
            it.close()
            detach(it)
        }
        nodes.clear()
    }

    override fun toString(): String {
        return "[PortGraph] label='$label' id='$id' nodes=${nodes.size}"
    }
}

fun<G: PortGraph<G, N>, N: PortNode<G>> G.Graph(builder: (G) -> G): G = builder(this)
