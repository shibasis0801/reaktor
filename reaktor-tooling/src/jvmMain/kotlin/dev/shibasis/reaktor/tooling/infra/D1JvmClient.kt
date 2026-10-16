package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.cloud.CloudflareLogin
import dev.shibasis.reaktor.tooling.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

internal class D1JvmClient(private val session: InfrastructureSession) {
    fun read(op: InfrastructureOperation.DatabaseRead, query: String?): QueryReceipt {
        require(!op.analyze) { "D1 reports execution statistics on query results; EXPLAIN ANALYZE is not supported" }
        val connection = op.connection as? DatabaseConnection.CloudflareD1 ?: error("Expected a Cloudflare D1 connection")
        require(connection.account.matches(Regex("[0-9a-f]{32}"))) { "Invalid Cloudflare account id" }
        require(connection.databaseId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid D1 database id" }
        val clock = QueryClock()
        val sql = query?.let { (if (op.explain) "EXPLAIN QUERY PLAN " else "") + "SELECT * FROM (\n$it\n) AS reaktor_query LIMIT ${op.maxRows + 1}" }
            ?: "SELECT 1 AS connected, count(*) AS tables FROM sqlite_schema WHERE type='table'"
        val uri = URI("https://api.cloudflare.com/client/v4/accounts/${connection.account}/d1/database/${connection.databaseId}/raw")
        val login = CloudflareLogin.forWorkspace(File(connection.workspace))
        val http = BoundedHttp(session)
        val payload = buildJsonObject { put("sql", sql) }.toString()
        clock.connected()
        val response = runBlocking {
            login.authenticated({ token ->
                runCatching { http.response(uri, payload, mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")) }
            }, { (it.exceptionOrNull() as? ProviderHttpFailure)?.status == 401 })
        }.getOrThrow()
        clock.executed()
        val body = Json.parseToJsonElement(response.body).jsonObject
        check(body["success"]?.jsonPrimitive?.booleanOrNull == true) { "D1 reported an unsuccessful query" }
        val statement = body["result"]?.jsonArray?.singleOrNull()?.jsonObject ?: error("D1 returned no statement result")
        val results = statement["results"]?.jsonObject ?: error("D1 returned no rows")
        val names = results["columns"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        require(names.size <= 512) { "Database result exceeds 512 columns" }
        val rows = results["rows"]?.jsonArray.orEmpty().map { it.jsonArray.toList() }
        require(rows.size <= op.maxRows + 1) { "D1 result exceeds row limit" }
        val meta = statement["meta"]?.jsonObject.orEmpty()
        val metrics = clock.metrics(http = true) + buildList {
            meta["duration"]?.jsonPrimitive?.doubleOrNull?.let { add(QueryMetric("Server execution", it.toString(), "ms")) }
            meta["rows_read"]?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Rows read", it, "rows")) }
            meta["rows_written"]?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Rows written", it, "rows")) }
            meta["served_by_region"]?.jsonPrimitive?.contentOrNull?.let { add(QueryMetric("Served by", it)) }
            add(QueryMetric("Response payload", response.body.toByteArray().size.toString(), "bytes", QueryMetricSource.Client))
        }
        val visible = if (query == null) rows else rows.take(op.maxRows)
        return QueryReceipt(provider = DatabaseProvider.D1.name, columns = names.map { QueryColumn(it, "unknown") }, rows = visible,
            truncated = rows.size > visible.size, metrics = metrics,
            plan = if (op.explain) QueryPlan("sqlite-query-plan", buildJsonObject {
                put("columns", JsonArray(names.map(::JsonPrimitive)))
                put("rows", JsonArray(rows.map(::JsonArray)))
            }) else null)
    }
}
