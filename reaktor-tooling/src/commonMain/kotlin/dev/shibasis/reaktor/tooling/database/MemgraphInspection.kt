package dev.shibasis.reaktor.tooling.database

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
            val read = entries.firstOrNull { text.startsWith(it.cypher + "\n") } ?: return null
            val bounds = Regex("SKIP ([0-9]+) LIMIT ([0-9]+)").matchEntire(text.substringAfterLast('\n')) ?: return null
            val offset = bounds.groupValues[1].toIntOrNull()?.takeIf { it in 0..1_000_000 } ?: return null
            val limit = bounds.groupValues[2].toIntOrNull()?.takeIf { it in 1..500 } ?: return null
            val request = MemgraphInspectionRequest(read, limit, offset)
            return request.takeIf { it.statement == text }
        }
    }
}

data class MemgraphInspectionRequest(val read: MemgraphInspection, val limit: Int, val offset: Int) {
    val statement get() = read.statement(limit, offset)
}
