package dev.shibasis.reaktor.portgraph.graph

import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
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
    private val nodesById = ConcurrentHashMap<Uuid, N>()

    val nodes: Collection<N>
        get() = nodesById.values()

    open fun attach(node: N): Boolean {
        return nodesById.putIfAbsent(node.id, node) == null
    }

    open fun detach(node: N): Boolean {
        val existing = nodesById[node.id] ?: return false
        if (existing !== node) return false
        nodesById.remove(node.id)
        return true
    }

    open fun close() {
        nodes.toList().forEach { node ->
            node.close()
            detach(node)
        }
    }

    fun node(id: Uuid): N? = nodesById[id]

    @JsExport.Ignore
    inline fun<reified T : N> nodesByType(): List<T> =
        nodes.filterIsInstance<T>()

    override fun toString(): String {
        return "[PortGraph] label='$label' id='$id' nodes=${nodes.size}"
    }
}

fun<G: PortGraph<G, N>, N: PortNode<G>> G.Graph(builder: (G) -> G): G = builder(this)
