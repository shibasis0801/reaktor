package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode

class DurableObjectIngress(graph: Graph, override val label: String) : BasicNode(graph), ShapeAttributes {
    override val kind = NodeKind.Actor
    override val shapeAttributes get() = mapOf("ingress" to "object:$label")
}
