package dev.shibasis.reaktor.tooling.infra

import java.io.File
import java.net.URI
import kotlinx.serialization.json.*

class SpanJvmClient(private val session: InfrastructureSession) {
    fun read(op: InfrastructureOperation.SpanRead, environment: Map<String, String>, timeoutMillis: Long): String {
        require(op.database.matches(Database)) { "Invalid span database" }
        require(op.windowMinutes in 1..10_080 && op.limit in 1..5_000)
        val port = KubernetesJvmClient(File(op.connection.kubeconfig), session)
            .portForward(op.connection.namespace, op.connection.service, op.connection.port).localPort
        val headers = buildMap {
            environment["CLICKHOUSE_USER"]?.let { put("X-ClickHouse-User", it) }
            environment["CLICKHOUSE_PASSWORD"]?.let { put("X-ClickHouse-Key", it) }
        }
        val seconds = (timeoutMillis / 1000).coerceAtLeast(1)
        val uri = URI("http://127.0.0.1:$port/?readonly=2&max_execution_time=$seconds")
        val http = BoundedHttp(session)
        return readReceipt(op) { sql -> http.request(uri, sql, headers) }
    }

    internal fun readReceipt(op: InfrastructureOperation.SpanRead, request: (String) -> String): String {
        require(op.database.matches(Database)) { "Invalid span database" }
        require(op.windowMinutes in 1..10_080 && op.limit in 1..5_000)
        val columns = request("SELECT name FROM system.columns WHERE database = '${op.database}' AND table = 'service_spans' FORMAT JSONEachRow")
            .lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject.getValue("name").jsonPrimitive.content }.toSet()
        require(columns.containsAll(RequiredColumns)) { "The service span table is missing required columns" }
        val selected = RequiredColumns.filterNot { it == "started_at" } + JoinColumns.map { if (it in columns) it else "'' AS $it" }
        val sql = """
            SELECT ${selected.joinToString(", ")}
            FROM ${op.database}.service_spans
            WHERE started_at > now64(3) - INTERVAL ${op.windowMinutes} MINUTE
            ORDER BY started_at DESC
            LIMIT ${op.limit}
            FORMAT JSONEachRow
        """.trimIndent()
        val rows = request(sql).lineSequence().filter(String::isNotBlank).map(Json::parseToJsonElement).toList()
        require(rows.size <= op.limit) { "Span response exceeds the requested row limit" }
        return Json.encodeToString(SpanReadReceipt.serializer(), SpanReadReceipt(
            database = op.database, windowMinutes = op.windowMinutes, limit = op.limit,
            columns = (RequiredColumns + JoinColumns).filter { it in columns }, rows = JsonArray(rows),
        ))
    }

    private companion object {
        val Database = Regex("[a-z_][a-z0-9_]{0,63}")
        val RequiredColumns = listOf("trace_id", "span_id", "parent_id", "phase", "island", "contract", "operation", "started_ms", "duration_ms", "status", "started_at")
        val JoinColumns = listOf("application_session", "environment", "build", "node", "graph_digest")
    }
}
