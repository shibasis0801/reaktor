package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.ResourceNode
import dev.shibasis.reaktor.graph.resourcePorts
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.contract.ResourceAccess

internal const val DurableObjectStorageResource = "Durable Object storage"

fun Graph.durableObjectStorage(): ResourceAccess = resourcePorts(listOf(
    Node { ResourceNode(it, DurableObjectStorageResource, NodeKind.Store, mapOf("storage" to "durable-object")) },
))
