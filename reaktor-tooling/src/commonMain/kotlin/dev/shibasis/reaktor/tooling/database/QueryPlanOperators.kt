package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.json.*

data class QueryPlanOperator(val depth: Int, val name: String, val attributes: Map<String, String> = emptyMap())

fun QueryPlan.operators(): List<QueryPlanOperator> {
    val result = mutableListOf<QueryPlanOperator>()
    fun add(depth: Int, name: String, attributes: Map<String, String> = emptyMap()) {
        require(result.size < 500 && depth in 0..64) { "Query plan exceeds the display limit" }
        result += QueryPlanOperator(depth, name, attributes)
    }
    fun value(element: JsonElement): String = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    when (format) {
        "postgres-json" -> {
            fun walk(node: JsonObject, depth: Int) {
                val attributes = node.filter { (key, item) -> key !in setOf("Node Type", "Plans") && item is JsonPrimitive }
                    .mapValues { value(it.value) }
                add(depth, node["Node Type"]?.let(::value) ?: "Plan", attributes)
                node["Plans"]?.jsonArray.orEmpty().forEach { walk(it.jsonObject, depth + 1) }
            }
            val roots = if (document is JsonArray) document else JsonArray(listOf(document))
            roots.forEach { root -> root.jsonObject["Plan"]?.jsonObject?.let { walk(it, 0) } }
        }
        "clickhouse-text" -> document.jsonArray.flatMap { value(it).lines() }.filter(String::isNotBlank).forEach { line ->
            add((line.takeWhile(Char::isWhitespace).length / 2).coerceAtMost(64), line.trim())
        }
        "memgraph-rows" -> {
            val names = document.jsonObject.getValue("columns").jsonArray.map(::value)
            document.jsonObject.getValue("rows").jsonArray.forEach { row ->
                val cells = row.jsonArray
                val name = cells.firstOrNull()?.let(::value).orEmpty()
                add((name.takeWhile(Char::isWhitespace).length / 2).coerceAtMost(64), name.trim(),
                    names.drop(1).mapIndexed { index, key -> key to value(cells[index + 1]) }.toMap())
            }
        }
        "sqlite-query-plan" -> {
            val rows = document.jsonArray.map { it.jsonObject }
            val byId = rows.associateBy { it["id"]?.jsonPrimitive?.content }
            fun depth(row: JsonObject, seen: Set<String> = emptySet()): Int {
                val parent = row["parent"]?.jsonPrimitive?.content ?: return 0
                val parentRow = byId[parent] ?: return 0
                require(parent !in seen && seen.size < 64) { "Query plan contains a parent cycle" }
                return 1 + depth(parentRow, seen + parent)
            }
            rows.forEach { add(depth(it), it["detail"]?.let(::value).orEmpty()) }
        }
    }
    return result
}
