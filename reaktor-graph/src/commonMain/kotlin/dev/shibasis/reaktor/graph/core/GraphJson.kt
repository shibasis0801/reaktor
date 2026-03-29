package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlinx.serialization.json.*

fun Graph.toJsonElement(): JsonElement = buildJsonObject {
    put("id", id.toString())
    put("label", label)
    put("nodeCount", nodes.size)

    putJsonArray("routes") {
        nodes.filterIsInstance<RouteNode<*, *>>().forEach { route ->
            addJsonObject {
                put("pattern", route.pattern.original)
                put("id", route.id.toString())
            }
        }
    }

    putJsonArray("containers") {
        nodes.filterIsInstance<ContainerNode>().forEach { container ->
            addJsonObject {
                put("pattern", container.route.pattern.original)
                put("id", container.id.toString())
                putJsonArray("children") {
                    container.graphs.forEach { child ->
                        add(child.toJsonElement())
                    }
                }
            }
        }
    }

    putJsonArray("nodes") {
        nodes.forEach { node ->
            addJsonObject {
                put("id", node.id.toString())
                put("type", node::class.simpleName ?: "Node")
                put("providers", node.providerPorts.flattenedValues().size)
                put("consumers", node.consumerPorts.flattenedValues().size)
                if (node is RouteNode<*, *>) {
                    put("pattern", node.pattern.original)
                }
            }
        }
    }
}
