package dev.shibasis.reaktor.cloud.clickhouse

import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.database.*
import dev.shibasis.reaktor.cloud.http.BoundedHttp
import dev.shibasis.reaktor.cloud.kubernetes.ClusterService
import dev.shibasis.reaktor.cloud.kubernetes.localPort

class ClickHouseJvmClient(private val session: InfrastructureSession) {
    fun read(service: ClusterService, op: QueryOptions, query: String?, env: Map<String, String>, timeout: Long): QueryReceipt {
        require(!op.analyze) { "ClickHouse reports execution statistics on query results; EXPLAIN ANALYZE is not supported" }
        val clock = QueryClock()
        val sql = query?.let { if (op.explain) "EXPLAIN $it" else "SELECT * FROM ($it) LIMIT ${op.maxRows + 1}" }
            ?: "SELECT currentUser() AS user, currentDatabase() AS database, count() AS tables FROM system.tables WHERE database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')"
        val headers = buildMap {
            env["CLICKHOUSE_USER"]?.let { put("X-ClickHouse-User", it) }
            env["CLICKHOUSE_PASSWORD"]?.let { put("X-ClickHouse-Key", it) }
        }
        val queryId = UUID.randomUUID().toString()
        val uri = URI("http://127.0.0.1:${service.localPort(session)}/?readonly=2&query_id=$queryId&max_execution_time=${(timeout / 1000).coerceAtLeast(1)}&max_result_rows=${op.maxRows + 1}&max_result_bytes=8388608&result_overflow_mode=throw")
        clock.connected()
        val response = BoundedHttp(session).response(uri, "$sql FORMAT JSONCompact", headers)
        clock.executed()
        val body = Json.parseToJsonElement(response.body).jsonObject
        val columns = body.getValue("meta").jsonArray.map {
            val column = it.jsonObject
            QueryColumn(column.getValue("name").jsonPrimitive.content, column.getValue("type").jsonPrimitive.content)
        }
        val values = body.getValue("data").jsonArray.map { row -> row.jsonArray.toList() }
        val stats = body["statistics"]?.jsonObject.orEmpty()
        val metrics = clock.metrics(http = true) + buildList {
            stats["elapsed"]?.jsonPrimitive?.doubleOrNull?.let { add(QueryMetric("Server execution", (it * 1000).toString(), "ms")) }
            stats["rows_read"]?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Rows read", it, "rows")) }
            stats["bytes_read"]?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Bytes read", it, "bytes")) }
            add(QueryMetric("Response payload", response.body.toByteArray().size.toString(), "bytes", QueryMetricSource.Client))
            val summary = response.header("X-ClickHouse-Summary")?.let { Json.parseToJsonElement(it).jsonObject }
            summary?.get("peak_memory_usage")?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Peak memory", it, "bytes")) }
        }
        return QueryReceipt(provider = "ClickHouse", columns = columns, rows = values,
            truncated = values.size > op.maxRows, metrics = metrics, queryId = response.header("X-ClickHouse-Query-Id") ?: queryId,
            plan = if (op.explain) QueryPlan("clickhouse-text", JsonArray(values.map { it.first() })) else null)
    }

    fun write(service: ClusterService, write: WriteStatement, env: Map<String, String>): QueryReceipt {
        val clock = QueryClock()
        val headers = buildMap {
            env["CLICKHOUSE_USER"]?.let { put("X-ClickHouse-User", it) }
            env["CLICKHOUSE_PASSWORD"]?.let { put("X-ClickHouse-Key", it) }
        }
        val queryId = UUID.randomUUID().toString()
        val uri = URI("http://127.0.0.1:${service.localPort(session)}/?query_id=$queryId&wait_end_of_query=1" +
            "&max_execution_time=${write.timeoutMillis / 1000}&default_format=JSONCompact")
        clock.connected()
        val response = BoundedHttp(session).response(uri, write.statement, headers)
        clock.executed()
        val body = response.body.takeIf(String::isNotBlank)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val columns = body?.get("meta")?.jsonArray.orEmpty().map {
            QueryColumn(it.jsonObject.getValue("name").jsonPrimitive.content, it.jsonObject.getValue("type").jsonPrimitive.content)
        }
        val rows = body?.get("data")?.jsonArray.orEmpty().map { it.jsonArray.toList() }
        val summary = response.header("X-ClickHouse-Summary")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty()
        val metrics = clock.metrics(http = true) + listOfNotNull(
            summary["written_rows"]?.jsonPrimitive?.contentOrNull?.let { QueryMetric("Rows written", it, "rows") },
            summary["written_bytes"]?.jsonPrimitive?.contentOrNull?.let { QueryMetric("Bytes written", it, "bytes") },
        )
        val text = if (body == null && response.body.isNotBlank()) response.body.lines().filter(String::isNotBlank).take(write.maxRows + 1) else emptyList()
        return if (text.isEmpty()) writeReceipt("ClickHouse", columns, rows, write.maxRows, metrics, response.header("X-ClickHouse-Query-Id") ?: queryId)
        else writeReceipt("ClickHouse", listOf(QueryColumn("result", "String")), text.map { listOf(JsonPrimitive(it)) }, write.maxRows, metrics, queryId)
    }
}
