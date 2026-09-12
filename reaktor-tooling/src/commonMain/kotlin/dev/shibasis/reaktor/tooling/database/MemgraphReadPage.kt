package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A bounded result page. Internal identities belong to its database snapshot, not a Reaktor graph. */
data class MemgraphReadNode(
    val id: String,
    val labels: String? = null,
    val properties: String? = null,
    val loaded: Boolean = false,
)

data class MemgraphReadRelationship(
    val id: String,
    val source: String,
    val target: String,
    val type: String,
    val properties: String?,
)

data class MemgraphReadPage(
    val nodes: List<MemgraphReadNode>,
    val relationships: List<MemgraphReadRelationship>,
) {
    companion object {
        /** Only records from the two registered record reads have graph identity. */
        fun from(read: MemgraphInspection, columns: List<String>, rows: List<List<String?>>): MemgraphReadPage {
            require(rows.size <= 500) { "A graph page is limited to 500 records" }
            val expected = when (read) {
                MemgraphInspection.Nodes -> listOf("node_id", "labels", "properties")
                MemgraphInspection.Relationships -> listOf("relationship_id", "source_id", "type", "target_id", "properties")
                else -> error("This catalog read has no node or relationship identities")
            }
            require(columns == expected && rows.all { it.size == expected.size }) { "Unexpected Memgraph record columns" }
            fun id(value: String?): String {
                require(value != null && value.toLongOrNull()?.let { it >= 0 } == true && value.toLong().toString() == value) {
                    "Invalid internal graph identity"
                }
                return value
            }
            val nodes = linkedMapOf<String, MemgraphReadNode>()
            val relationships = linkedMapOf<String, MemgraphReadRelationship>()
            rows.forEach { row ->
                if (read == MemgraphInspection.Nodes) {
                    val node = MemgraphReadNode(id(row[0]), row[1], row[2], loaded = true)
                    require(nodes.put(node.id, node) == null) { "Duplicate node identity in result page" }
                } else {
                    val source = id(row[1])
                    val target = id(row[3])
                    val rawType = requireNotNull(row[2]) { "Missing relationship type" }
                    val type = (runCatching { Json.parseToJsonElement(rawType) }.getOrNull() as? JsonPrimitive)?.content ?: rawType
                    require(type.isNotBlank()) { "Missing relationship type" }
                    val relationship = MemgraphReadRelationship(id(row[0]), source, target, type, row[4])
                    require(relationships.put(relationship.id, relationship) == null) { "Duplicate relationship identity in result page" }
                    nodes.getOrPut(source) { MemgraphReadNode(source) }
                    nodes.getOrPut(target) { MemgraphReadNode(target) }
                }
            }
            return MemgraphReadPage(nodes.values.toList(), relationships.values.toList())
        }
    }
}
