@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.ui.Navigable

// Similar to React Flow + dagre/ELK pipelines, semantic labeling is resolved before measurement.
// That keeps layout deterministic and lets people change visual composition without touching the
// graph extraction path.
internal fun nodeTitle(node: GraphNode): String = when (node) {
    is RouteNode<*, *> -> {
        val pattern = node.pattern.original.trim()
        when {
            pattern.isNotEmpty() -> pattern
            !node.label.isNullOrBlank() && !looksLikeUuid(node.label) -> node.label
            else -> "Root Route"
        }
    }
    else -> {
        val label = node.label.trim()
        if (label.isBlank() || looksLikeUuid(label)) node::class.simpleName ?: "Node" else label
    }
}

internal fun nodeSubtitle(node: GraphNode): String? = when (node) {
    is RouteNode<*, *> -> node::class.simpleName
    is ContainerNode -> "${node.graphs.size} child graphs"
    else -> null
}

internal fun pinLabel(key: String, typeName: String): String {
    val trimmed = key.trim()
    return if (trimmed.isEmpty() || looksLikeUuid(trimmed)) shortType(typeName) else trimmed
}

internal fun shortType(type: String): String {
    var name = type.substringAfterLast('.')
    val genericIndex = name.indexOf('<')
    if (genericIndex > 0) name = name.substring(0, genericIndex)
    return name
}

internal fun looksLikeUuid(value: String): Boolean {
    val parts = value.split('-')
    if (parts.size != 5) return false
    val lengths = intArrayOf(8, 4, 4, 4, 12)
    return parts.withIndex().all { (index, part) ->
        part.length == lengths[index] && part.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

internal fun graphLabel(graph: Graph): String =
    (graph as? Navigable)?.label?.ifBlank { graph.label } ?: graph.label.ifBlank { "Root" }
