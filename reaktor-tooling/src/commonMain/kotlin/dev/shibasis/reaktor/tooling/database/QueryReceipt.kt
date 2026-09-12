package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QueryColumn(val name: String, val type: String, val nullable: Boolean? = null)

@Serializable
enum class QueryMetricSource { Client, Server }

@Serializable
data class QueryMetric(val name: String, val value: String, val unit: String = "", val source: QueryMetricSource = QueryMetricSource.Server)

@Serializable
data class QueryPlan(val format: String, val document: JsonElement, val analyzed: Boolean = false)

/** Provider facts stay separate from the elapsed time observed by the calling kernel. */
@Serializable
data class QueryReceipt(
    val protocol: String = "reaktor.query.v1",
    val provider: String,
    val columns: List<QueryColumn>,
    val rows: List<List<JsonElement>>,
    val truncated: Boolean = false,
    val metrics: List<QueryMetric> = emptyList(),
    val plan: QueryPlan? = null,
    val queryId: String? = null,
    val warnings: List<String> = emptyList(),
) {
    fun validate(maxRows: Int, expectedProvider: String? = null) {
        require(protocol == "reaktor.query.v1") { "Unsupported query receipt protocol" }
        require(expectedProvider == null || provider == expectedProvider) { "Query receipt provider changed" }
        require(maxRows in 1..500 && columns.size <= 512 && rows.size <= maxRows + 1) { "Query receipt exceeds its limits" }
        require(rows.all { it.size == columns.size }) { "Query result columns and cells do not match" }
        require(metrics.size <= 128 && warnings.size <= 32) { "Query metadata exceeds its limits" }
    }
}
