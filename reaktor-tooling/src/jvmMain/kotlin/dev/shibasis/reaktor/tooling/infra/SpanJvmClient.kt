package dev.shibasis.reaktor.tooling.infra

import java.io.File
import java.net.URI

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
        val sql = """
            SELECT trace_id, span_id, parent_id, phase, island, contract, operation, started_ms, duration_ms, status
            FROM ${op.database}.service_spans
            WHERE started_at > now64(3) - INTERVAL ${op.windowMinutes} MINUTE
            ORDER BY started_at DESC
            LIMIT ${op.limit}
            FORMAT JSONEachRow
        """.trimIndent()
        val seconds = (timeoutMillis / 1000).coerceAtLeast(1)
        val uri = URI("http://127.0.0.1:$port/?readonly=2&max_execution_time=$seconds")
        val rows = BoundedHttp(session).request(uri, sql, headers).lines().filter(String::isNotBlank)
        return rows.joinToString(",", prefix = "[", postfix = "]")
    }

    private companion object {
        val Database = Regex("[a-z_][a-z0-9_]{0,63}")
    }
}
