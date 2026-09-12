package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.json.*

/** Derived from the provider document, leaving the v1 wire receipt unchanged. */
data class QueryPlanOperator(
    val depth: Int,
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val id: String = "",
    val parentId: String? = null,
    val estimatedRows: Double? = null,
    val actualRowsPerLoop: Double? = null,
    val loops: Double? = null,
    val inclusiveMillisPerLoop: Double? = null,
    val peakMemoryBytes: Long? = null,
    val diskBytes: Long? = null,
    val sharedHitBlocks: Long? = null,
    val sharedReadBlocks: Long? = null,
    val tempReadBlocks: Long? = null,
    val tempWrittenBlocks: Long? = null,
    val removedSubplans: Long? = null,
    val parallelAware: Boolean? = null,
) {
    /** Includes descendants and can overlap parallel workers: never sum this as wall time. */
    val accumulatedInclusiveMillis: Double? get() = finiteProduct(inclusiveMillisPerLoop, loops)
    val accumulatedRows: Double? get() = finiteProduct(actualRowsPerLoop, loops)
    val rowEstimateErrorFactor: Double? get() {
        val actual = actualRowsPerLoop?.takeIf { it > 0 && loops != 0.0 } ?: return null
        val estimate = estimatedRows?.takeIf { it > 0 } ?: return null
        return maxOf(actual / estimate, estimate / actual).takeIf(Double::isFinite)
    }
    val zeroRowEstimateMismatch: Boolean get() = loops != 0.0 && actualRowsPerLoop != null && estimatedRows != null &&
        ((actualRowsPerLoop == 0.0) != (estimatedRows == 0.0))
    private fun finiteProduct(a: Double?, b: Double?): Double? = if (a == null || b == null) null else (a * b).takeIf(Double::isFinite)
}

fun QueryPlan.operators(): List<QueryPlanOperator> {
    val result = mutableListOf<QueryPlanOperator>()
    fun add(depth: Int, name: String, attributes: Map<String, String> = emptyMap()) {
        require(result.size < 500 && depth in 0..64) { "Query plan exceeds the display limit" }
        result += QueryPlanOperator(depth, name, attributes, id = "node-${result.size}",
            parentId = result.lastOrNull { it.depth < depth }?.id)
    }
    fun value(element: JsonElement): String = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
    when (format) {
        "postgres-json" -> {
            fun walk(node: JsonObject, depth: Int) {
                val attributes = node.filter { (key, item) -> key !in setOf("Node Type", "Plans") && item is JsonPrimitive }
                    .mapValues { value(it.value) }
                add(depth, node["Node Type"]?.let(::value) ?: "Plan", attributes)
                fun number(key: String): Double? = (node[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
                fun count(key: String): Long? = (node[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                fun bytes(key: String): Long? = count(key)?.takeIf { it <= Long.MAX_VALUE / 1024 }?.times(1024)
                val analyzed = this@operators.analyzed
                result[result.lastIndex] = result.last().copy(
                    estimatedRows = number("Plan Rows"),
                    actualRowsPerLoop = if (analyzed) number("Actual Rows") else null,
                    loops = if (analyzed) number("Actual Loops") else null,
                    inclusiveMillisPerLoop = if (analyzed) number("Actual Total Time") else null,
                    peakMemoryBytes = if (analyzed) bytes("Peak Memory Usage") ?: bytes("Memory Usage") ?:
                        bytes("Sort Space Used").takeIf { node["Sort Space Type"]?.jsonPrimitive?.content == "Memory" } else null,
                    diskBytes = if (analyzed) bytes("Disk Usage") ?: bytes("Sort Space Used").takeIf {
                        node["Sort Space Type"]?.jsonPrimitive?.content == "Disk" } else null,
                    sharedHitBlocks = if (analyzed) count("Shared Hit Blocks") else null,
                    sharedReadBlocks = if (analyzed) count("Shared Read Blocks") else null,
                    tempReadBlocks = if (analyzed) count("Temp Read Blocks") else null,
                    tempWrittenBlocks = if (analyzed) count("Temp Written Blocks") else null,
                    removedSubplans = count("Subplans Removed"),
                    parallelAware = (node["Parallel Aware"] as? JsonPrimitive)?.booleanOrNull,
                )
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
                    names.drop(1).mapIndexedNotNull { index, key -> cells.getOrNull(index + 1)?.let { key to value(it) } }.toMap())
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
            rows.forEach {
                add(depth(it), it["detail"]?.let(::value).orEmpty())
                val parent = it["parent"]?.jsonPrimitive?.content
                result[result.lastIndex] = result.last().copy(id = it["id"]?.jsonPrimitive?.content ?: result.last().id,
                    parentId = parent?.takeIf(byId::containsKey))
            }
        }
    }
    return result
}
