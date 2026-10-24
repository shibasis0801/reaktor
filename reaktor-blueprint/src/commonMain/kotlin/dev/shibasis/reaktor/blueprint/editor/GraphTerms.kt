package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.NodeShape

interface GraphTerms {
    val kinds: Boolean get() = true
    val types: Boolean get() = true
    val scope: String get() = "graph"
    val upstream: String get() = "Select what it depends on"
    val downstream: String get() = "Select what depends on it"
    val subgraphs: Boolean get() = true
    val settings: Boolean get() = true
    fun fits(provided: String, needed: String): Boolean = provided == needed
    fun conversion(provided: String, needed: String): String? = null
    fun cycle(names: List<String>): String = "${(names + names.first()).joinToString(" → ")} form a cycle"
    fun port(node: NodeShape, key: String): String = key
    fun end(node: NodeShape, key: String): String = "${node.label}.${port(node, key)}"
    fun summary(node: NodeShape, change: Change): String =
        if (change == Change.Added) "${node.kind.name} · ${node.type} · ${node.ports.size} ports" else "${node.kind.name} · ${node.type}"
    fun rows(node: NodeShape): List<String>? = null
    fun row(node: NodeShape, title: String): NodeShape = node

    companion object Ports : GraphTerms
}
