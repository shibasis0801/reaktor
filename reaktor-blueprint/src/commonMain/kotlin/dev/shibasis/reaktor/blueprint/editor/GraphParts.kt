package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape

const val PartPrefix = "part."
const val PartIdKey = "part.id"
const val PartUseKey = "part.use"
const val PartNodeKey = "part.node"
const val PartRevisionKey = "part.revision"

data class PartUse(val part: String, val use: String, val revision: Int, val nodes: List<String>)

fun GraphPart.signature(): List<PortShape> {
    val nodes = changes.filter { it.op == "addNode" }.mapNotNull { it.node }
    val wires = changes.filter { it.op == "connect" }.mapNotNull { it.wire }
    val fed = wires.mapTo(hashSetOf()) { it.consumer to it.consumerPort }
    val used = wires.mapTo(hashSetOf()) { it.provider to it.providerPort }
    return nodes.flatMap { node ->
        node.ports.filter { !it.internal && (node.id to it.key) !in (if (it.provides) used else fed) }.map { it.copy(key = node.id + FoldSeparator + it.key) }
    }
}

fun GraphShape.uses(part: String): List<PartUse> = nodes.filter { it.attributes[PartIdKey] == part }.groupBy { it.attributes[PartUseKey].orEmpty() }
    .map { (use, members) -> PartUse(part, use, members.first().attributes[PartRevisionKey]?.toIntOrNull() ?: 0, members.map { it.id }) }

val NodeShape.part: String? get() = attributes[PartIdKey]

internal fun NodeShape.linked(set: ChangeSet, use: String, original: String): NodeShape =
    copy(attributes = attributes.filterKeys { !it.startsWith(PartPrefix) } + mapOf(PartIdKey to set.id, PartUseKey to use, PartNodeKey to original, PartRevisionKey to set.revision.toString()))
