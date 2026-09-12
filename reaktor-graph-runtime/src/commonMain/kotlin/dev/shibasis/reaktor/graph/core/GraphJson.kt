package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlinx.serialization.json.*

/**
 * Full recursive serialization of a Graph to JSON.
 * Includes all nodes, ports, edges, and child graphs.
 */
fun Graph.toJsonElement(): JsonElement = buildJsonObject {
    put("id", id.toString())
    put("label", label)

    putJsonArray("nodes") {
        nodes.forEach { node ->
            addJsonObject {
                put("id", node.id.toString())
                put("type", node::class.simpleName ?: "Node")
                put("label", node.label)
                if (node is RouteNode<*, *>) {
                    put("pattern", node.pattern.original)
                }
                if (node is ContainerNode) {
                    put("childGraphCount", node.graphs.size)
                }
                putJsonArray("providers") {
                    node.providerPorts.flattenedValues().forEach { port ->
                        addJsonObject {
                            put("key", port.key.key)
                            put("type", port.type.type)
                            put("edgeCount", port.edges.size)
                        }
                    }
                }
                putJsonArray("consumers") {
                    node.consumerPorts.flattenedValues().forEach { port ->
                        addJsonObject {
                            put("key", port.key.key)
                            put("type", port.type.type)
                            put("connected", port.isConnected())
                        }
                    }
                }
            }
        }
    }

    // Port wiring edges (consumer → provider connections)
    putJsonArray("edges") {
        nodes.forEach { node ->
            node.consumerPorts.flattenedValues()
                .filter { it.isConnected() }
                .forEach { consumer ->
                    val edge = consumer.edge ?: return@forEach
                    val provider = edge.provider
                    val targetNode = edge.destination
                    addJsonObject {
                        put("type", "port")
                        put("sourceNode", node.id.toString())
                        put("sourcePort", consumer.key.key)
                        put("targetNode", (targetNode as? Node)?.id?.toString() ?: "")
                        put("targetPort", provider.key.key)
                        put("portType", consumer.type.type)
                    }
                }
        }

        // Navigation edges (route → route)
        nodes.filterIsInstance<RouteNode<*, *>>().forEach { routeNode ->
            routeNode.navigationTargets().forEach { targetNode ->
                addJsonObject {
                    put("type", "navigation")
                    put("sourceNode", routeNode.id.toString())
                    put("sourcePattern", routeNode.pattern.original)
                    put("targetNode", targetNode.id.toString())
                    put("targetPattern", targetNode.pattern.original)
                    put("crossGraph", routeNode.graph != targetNode.graph)
                }
            }
        }
    }

    // Child graphs (from containers) — recursive
    val containers = nodes.filterIsInstance<ContainerNode>()
    if (containers.isNotEmpty()) {
        putJsonArray("children") {
            containers.forEach { container ->
                container.graphs.forEach { child ->
                    add(child.toJsonElement())
                }
            }
        }
    }
}
