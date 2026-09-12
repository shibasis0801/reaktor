package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Closed read catalog for hosts that have console access but no independently verified read role. */
enum class MemgraphInspection(val label: String, val cypher: String) {
    Labels("Labels", "MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS node_count ORDER BY label"),
    Properties("Node properties", "MATCH (n) UNWIND labels(n) AS label UNWIND keys(n) AS property RETURN DISTINCT label, property ORDER BY label, property"),
    Nodes("Nodes", "MATCH (n) RETURN id(n) AS node_id, labels(n) AS labels, properties(n) AS properties ORDER BY node_id"),
    Relationships("Relationships", "MATCH (a)-[r]->(b) RETURN id(r) AS relationship_id, id(a) AS source_id, type(r) AS type, id(b) AS target_id, properties(r) AS properties ORDER BY relationship_id"),
    RelationshipTypes("Relationship types", "MATCH (a)-[r]->(b) RETURN labels(a) AS source_labels, type(r) AS type, labels(b) AS target_labels, count(*) AS relationship_count ORDER BY type"),
    Statistics("Node counts", "MATCH (n) RETURN count(n) AS nodes, count(DISTINCT labels(n)) AS label_sets"),
    ;

    fun statement(limit: Int = 100, offset: Int = 0): String {
        require(limit in 1..500) { "Memgraph inspection limit must be between 1 and 500" }
        require(offset in 0..1_000_000) { "Memgraph inspection offset is out of range" }
        return "$cypher\nSKIP $offset LIMIT $limit"
    }

    companion object {
        fun parse(statement: String): MemgraphInspectionRequest? {
            val text = statement.trim().removeSuffix(";").trimEnd()
            val bounds = Regex("SKIP ([0-9]+) LIMIT ([0-9]+)").matchEntire(text.substringAfterLast('\n')) ?: return null
            val offset = bounds.groupValues[1].toIntOrNull()?.takeIf { it in 0..1_000_000 } ?: return null
            val limit = bounds.groupValues[2].toIntOrNull()?.takeIf { it in 1..500 } ?: return null
            val read = entries.firstOrNull { text.startsWith(it.cypher + "\n") }
            val request = if (read != null) MemgraphInspectionRequest(read, limit, offset)
                else return MemgraphInspectionRequest.parseExploration(text, limit, offset)
            return request.takeIf { it.statement == text }
        }
    }
}

@kotlinx.serialization.Serializable
enum class MemgraphDirection { Both, Outgoing, Incoming }

data class MemgraphInspectionRequest(val read: MemgraphInspection, val limit: Int, val offset: Int,
    val nodeId: Long? = null, val direction: MemgraphDirection = MemgraphDirection.Both,
    val relationshipType: String? = null) {
    init {
        require(limit in 1..500 && offset in 0..1_000_000)
        require(nodeId == null || nodeId >= 0)
        require(nodeId == null && direction == MemgraphDirection.Both && relationshipType == null ||
            nodeId != null && read in setOf(MemgraphInspection.Nodes, MemgraphInspection.Relationships))
        require(read != MemgraphInspection.Nodes || relationshipType == null && direction == MemgraphDirection.Both)
        require(relationshipType == null || relationshipType.length in 1..128 && relationshipType.none(Char::isISOControl))
    }
    val cypher: String get() {
        if (nodeId == null) return read.cypher
        if (read == MemgraphInspection.Nodes) return read.cypher.replace("MATCH (n) RETURN", "MATCH (n) WHERE id(n) = $nodeId RETURN")
        val predicate = when (direction) {
            MemgraphDirection.Both -> "(id(a) = $nodeId OR id(b) = $nodeId)"
            MemgraphDirection.Outgoing -> "id(a) = $nodeId"
            MemgraphDirection.Incoming -> "id(b) = $nodeId"
        }
        val type = relationshipType?.let { " AND type(r) = ${Json.encodeToString(it)}" }.orEmpty()
        return "MATCH (a)-[r]->(b) WHERE $predicate$type RETURN id(r) AS relationship_id, id(a) AS source_id, type(r) AS type, id(b) AS target_id, properties(r) AS properties, labels(a) AS source_labels, properties(a) AS source_properties, labels(b) AS target_labels, properties(b) AS target_properties ORDER BY relationship_id"
    }
    val statement get() = "$cypher\nSKIP $offset LIMIT $limit"

    companion object {
        internal fun parseExploration(text: String, limit: Int, offset: Int): MemgraphInspectionRequest? = runCatching {
            val id = Regex("id\\([nab]\\) = ([0-9]+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val read = if (text.startsWith("MATCH (n) WHERE")) MemgraphInspection.Nodes else MemgraphInspection.Relationships
            val direction = if (text.startsWith("MATCH (a)-[r]->(b) WHERE id(a)")) MemgraphDirection.Outgoing
                else if (text.startsWith("MATCH (a)-[r]->(b) WHERE id(b)")) MemgraphDirection.Incoming else MemgraphDirection.Both
            val type = text.substringBeforeLast(" RETURN ").substringAfter(" AND type(r) = ", "")
                .takeIf(String::isNotEmpty)?.let { Json.decodeFromString<String>(it) }
            MemgraphInspectionRequest(read, limit, offset, id, direction, type).takeIf { it.statement == text }
        }.getOrNull()
    }
}
