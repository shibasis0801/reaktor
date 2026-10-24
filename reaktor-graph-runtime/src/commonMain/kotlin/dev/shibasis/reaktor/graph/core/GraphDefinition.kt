package dev.shibasis.reaktor.graph.core

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

fun GraphShape.withDefinitionIdentity(): GraphShape {
    val byScope = scopes.associateBy { it.id }
    fun path(scope: ScopeShape): String? {
        val visited = mutableSetOf<String>()
        val segments = mutableListOf<String>()
        var current: ScopeShape? = scope
        while (current != null) {
            if (!visited.add(current.id) || current.label.isBlank()) return null
            segments += current.label
            val parent = current.parent
            current = if (parent == null) null else byScope[parent] ?: return null
        }
        return segments.reversed().joinToString("\u0000")
    }
    val paths = scopes.associate { it.id to path(it) }
    val uniqueScopes = paths.values.filterNotNull().groupingBy { it }.eachCount()
    val definedScopes = scopes.map { scope ->
        val identity = paths[scope.id]?.takeIf { uniqueScopes[it] == 1 }
        scope.copy(definitionId = identity?.let { "graph:" + digest("scope-v1\u0000$it") }.orEmpty())
    }
    val scopeIds = definedScopes.associate { it.id to it.definitionId }
    val candidates = nodes.associate { node -> node.id to scopeIds[node.scope]?.takeIf(String::isNotEmpty)?.let {
        listOf(it, node.label, node.type, node.route.orEmpty()).joinToString("\u0000")
    } }
    val counts = candidates.values.filterNotNull().groupingBy { it }.eachCount()
    val identifiedNodes = nodes.map { node ->
        val identity = candidates[node.id]?.takeIf { counts[it] == 1 && node.label.isNotBlank() }
        val id = identity?.let { "node:" + digest("node-v1\u0000$it") }.orEmpty()
        node.copy(definitionId = id)
    }
    val nodeIds = identifiedNodes.associate { it.id to it.definitionId }
    fun portKey(key: String, type: String): String? =
        if (type == "NavBinding" && key in nodeIds) nodeIds[key]?.takeIf(String::isNotEmpty) else key
    val definedNodes = identifiedNodes.map { node -> node.copy(ports = node.ports.map { port ->
        val key = portKey(port.key, port.type)
        port.copy(definitionId = if (node.definitionId.isEmpty() || key == null) "" else
            "port:" + digest(listOf("port-v1", node.definitionId, key, port.type, port.provides.toString()).joinToString("\u0000")))
    }) }
    val definedWires = wires.map { wire ->
        val consumer = nodeIds[wire.consumer].orEmpty()
        val provider = nodeIds[wire.provider].orEmpty()
        val consumerPort = portKey(wire.consumerPort, wire.type)
        val providerPort = portKey(wire.providerPort, wire.type)
        wire.copy(definitionId = if (consumer.isEmpty() || provider.isEmpty() || consumerPort == null || providerPort == null) "" else
            "edge:" + digest(listOf("wire-v1", consumer, consumerPort, provider, providerPort, wire.type).joinToString("\u0000")))
    }
    val canonical = copy(scopes = definedScopes, nodes = definedNodes, wires = definedWires).definitionStructure()
    return copy(scopes = definedScopes, nodes = definedNodes, wires = definedWires,
        definitionDigest = digest("graph-definition-v2\u0000" + Json.encodeToString(GraphShape.serializer(), canonical)))
}



fun GraphShape.definitionArtifact(): GraphShape {
    val identified = withDefinitionIdentity()
    require(definitionDigest.isNotEmpty() && definitionDigest == identified.definitionDigest) { "Graph definition digest does not match its topology" }
    require(identified.scopes.all { it.definitionId.isNotEmpty() } && identified.nodes.all { it.definitionId.isNotEmpty() && it.ports.all { port -> port.definitionId.isNotEmpty() } } && identified.wires.all { it.definitionId.isNotEmpty() }) { "Ambiguous graph definitions cannot be retained as exact topology" }
    return identified.definitionStructure().copy(definitionDigest = identified.definitionDigest)
}

private fun GraphShape.definitionStructure(): GraphShape {
    val definedScopes = scopes
    val definedNodes = nodes
    val definedWires = wires
    val scopeIds = scopes.associate { it.id to it.definitionId }
    val nodeIds = nodes.associate { it.id to it.definitionId }
    fun portKey(key: String, type: String): String? =
        if (type == "NavBinding" && key in nodeIds) nodeIds[key]?.takeIf(String::isNotEmpty) else key
    return GraphShape(
        definedScopes.map { it.copy(id = it.definitionId, parent = it.parent?.let(scopeIds::get), container = it.container?.let(nodeIds::get), active = false, backStack = emptyList()) }.sortedBy { it.id },
        definedNodes.map { it.copy(id = it.definitionId, scope = scopeIds[it.scope].orEmpty(), attachedTo = it.attachedTo?.let(nodeIds::get), lifecycle = null,
            ports = it.ports.map { port -> port.copy(key = portKey(port.key, port.type).orEmpty(), wiring = PortWiring.Unused, connections = 0) }.sortedWith(compareBy({ port -> port.key }, { port -> port.type }, { port -> port.provides })),
            attributes = it.attributes.filterKeys { key -> key in setOf("contract", "version", "role", "service") }.entries.sortedBy { entry -> entry.key }.associate { entry -> entry.toPair() },
            operations = it.operations.sortedBy { operation -> operation.operation },
              contract = it.contract?.copy(operations = emptyList(), channels = emptyList())) }.sortedWith(compareBy({ it.id }, { it.type }, { it.label })),
        definedWires.map { it.copy(consumer = nodeIds[it.consumer].orEmpty(), consumerPort = portKey(it.consumerPort, it.type).orEmpty(),
            provider = nodeIds[it.provider].orEmpty(), providerPort = portKey(it.providerPort, it.type).orEmpty()) }.sortedBy { it.definitionId },
        routes.map { it.copy(from = nodeIds[it.from].orEmpty(), to = nodeIds[it.to].orEmpty()) }.sortedWith(compareBy({ it.from }, { it.to })),
    )
}

private fun digest(value: String): String = value.encodeUtf8().sha256().hex()
