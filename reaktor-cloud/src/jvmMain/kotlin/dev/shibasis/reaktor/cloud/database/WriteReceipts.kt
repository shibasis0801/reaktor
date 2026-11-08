package dev.shibasis.reaktor.cloud.database

import kotlinx.serialization.json.JsonElement

internal fun writeMetrics(affected: Long, statements: Int) = listOf(
    QueryMetric("Rows affected", affected.toString(), "rows"), QueryMetric("Statements", statements.toString()))

internal fun writeReceipt(provider: String, columns: List<QueryColumn>, rows: List<List<JsonElement>>, maxRows: Int,
    metrics: List<QueryMetric>, queryId: String? = null, warnings: List<String> = emptyList()) =
    QueryReceipt(provider = provider, columns = columns, rows = rows.take(maxRows), truncated = rows.size > maxRows,
        metrics = metrics, queryId = queryId, warnings = warnings)
