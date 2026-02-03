package dev.shibasis.reaktor.portgraph.node

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import kotlin.js.JsExport
import kotlin.uuid.Uuid

@JsExport
open class PortNode<G: PortGraph<*, *>>(
    open val graph: G,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    portCapability: PortCapability = PortCapabilityImpl()
): Unique, Visitable, PortCapability by portCapability {

    open fun close() {
        consumerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
    }

    override fun toString(): String {
        return "[${this::class.simpleName}] label='$label' id='$id' inputs=${consumerPorts.flattenedValues().size} outputs=${providerPorts.flattenedValues().size}"
    }
}

fun<G: PortGraph<G, N>, N: PortNode<G>> G.Node(builder: (G) -> N): N = builder(this)
