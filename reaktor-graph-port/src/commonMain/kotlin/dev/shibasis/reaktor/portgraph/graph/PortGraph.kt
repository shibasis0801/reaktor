package dev.shibasis.reaktor.portgraph.graph

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.attach.Attachable
import dev.shibasis.reaktor.portgraph.attach.Attachments
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.traversal.Visitable
import kotlinx.atomicfu.atomic
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
open class PortGraph<Self: PortGraph<Self, N>, N: PortNode<Self>>(
    override val id: Uuid = Uuid.random(),
    override val label: String = ""
): Unique, Visitable, Attachable by Attachments() {
    private val nodesById = atomic<Map<Uuid, N>>(emptyMap())

    val nodes: Collection<N>
        get() = nodesById.value.values

    open fun attach(node: N): Boolean {
        while (true) {
            val current = nodesById.value
            if (node.id in current) return false
            if (nodesById.compareAndSet(current, current + (node.id to node))) return true
        }
    }

    open fun detach(node: N): Boolean {
        while (true) {
            val current = nodesById.value
            if (current[node.id] !== node) return false
            if (nodesById.compareAndSet(current, current - node.id)) return true
        }
    }

    @JsExport.Ignore
    open fun members(): List<Visitable> = nodes.toList()

    open fun close() {
        nodes.toList().forEach { node ->
            node.close()
            detach(node)
        }
    }

    fun node(id: Uuid): N? = nodesById.value[id]

    @JsExport.Ignore
    inline fun<reified T : N> nodesByType(): List<T> =
        nodes.filterIsInstance<T>()

    override fun toString(): String {
        return "[PortGraph] label='$label' id='$id' nodes=${nodes.size}"
    }
}

fun<G: PortGraph<G, N>, N: PortNode<G>> G.Graph(builder: (G) -> G): G = builder(this)
