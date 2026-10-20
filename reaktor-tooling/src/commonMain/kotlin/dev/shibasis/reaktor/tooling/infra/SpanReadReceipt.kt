package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class SpanReadReceipt(
    val version: Int = 1,
    val database: String,
    val windowMinutes: Int,
    val limit: Int,
    val columns: List<String>,
    val rows: JsonArray,
    val filter: SpanReadFilter = SpanReadFilter(),
)

@Serializable
data class SpanReadFilter(
    val trace: String? = null,
    val session: String? = null,
    val build: String? = null,
    val node: String? = null,
    val graphDigest: String? = null,
    val port: String? = null,
    val activation: String? = null,
) {
    fun columns(): Map<String, String> = listOf(
        "trace_id" to trace, "application_session" to session, "build" to build, "node" to node,
        "graph_digest" to graphDigest, "port" to port, "activation" to activation,
    ).mapNotNull { (column, value) -> value?.let { column to it } }.toMap()

    fun validate() {
        require(columns().values.all { it.length in 1..256 && it.none(Char::isISOControl) }) { "Invalid span filter" }
        require(trace == null || trace.matches(Regex("[0-9a-f]{32}"))) { "Invalid trace id" }
    }

    fun covers(requested: SpanReadFilter): Boolean =
        columns().all { (column, value) -> requested.columns()[column] == value }
}
