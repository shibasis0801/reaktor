package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Config
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.AccessMode
import org.neo4j.driver.TransactionConfig
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.Locale

class DatabaseJvmClient(private val session: InfrastructureSession) {
    fun execute(op: InfrastructureOperation.DatabaseRead, environment: Map<String, String>, timeoutMillis: Long): String {
        require(op.maxRows in 1..500)
        val query = op.queryFile?.let { path ->
            val file = File(path)
            checkPrivateFile(file, false)
            require(file.length() in 1..262_144) { "Invalid query size" }
            file.readText()
        }
        val resultFile = op.resultFile?.let(::File)
        require((query == null) == (resultFile == null)) { "Query requires a private result destination" }
        resultFile?.let { checkPrivateFile(it, true) }
        val receipt = when (op.engine) {
            DatabaseProvider.Postgres -> postgres(op, query, environment, timeoutMillis)
            DatabaseProvider.Memgraph -> memgraph(op, query, environment, timeoutMillis)
            DatabaseProvider.ClickHouse -> clickhouse(op, query, environment, timeoutMillis)
        }
        receipt.validate(op.maxRows, op.engine.name)
        val output = if (op.resultFormat == DatabaseResultFormat.QueryReceipt) Json.encodeToString(receipt)
            else csvRow(receipt.columns.map { it.name }) + receipt.rows.joinToString("") { row ->
                csvRow(row.map { value -> when (value) { JsonNull -> null; is JsonPrimitive -> value.content; else -> value.toString() } })
            }
        require(output.toByteArray().size <= 8_388_608) { "Database result exceeds 8 MiB" }
        if (resultFile == null) return output
        Files.newByteChannel(resultFile.toPath(), setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use {
            val bytes = java.nio.ByteBuffer.wrap(output.toByteArray())
            while (bytes.hasRemaining()) it.write(bytes)
        }
        return "Completed native ${op.engine} read; result is in the private session channel"
    }

    private fun postgres(op: InfrastructureOperation.DatabaseRead, query: String?, env: Map<String, String>, timeout: Long): QueryReceipt {
        val clock = QueryClock()
        require(op.connection == DatabaseConnection.PostgresEnvironment)
        fun required(key: String) = requireNotNull(env[key]?.takeIf(String::isNotBlank)) { "Missing PostgreSQL connection field $key" }
        val host = required("PGHOST")
        val user = required("PGUSER")
        val dataSource = PGSimpleDataSource().apply {
            serverNames = arrayOf(host)
            portNumbers = intArrayOf(required("PGPORT").toInt())
            databaseName = required("PGDATABASE")
            setUser(user)
            password = required("PGPASSWORD")
            sslMode = env["PGSSLMODE"] ?: "require"
            connectTimeout = 5
            socketTimeout = (timeout / 1000).toInt().coerceAtLeast(1)
            applicationName = "reaktor-kernel"
        }
        val connection = session.own(dataSource.connection)
        connection.isReadOnly = true
        connection.autoCommit = false
        if (query != null) {
            require(env["REAKTOR_PG_INSPECTOR_POLICY"] == "least-privilege-inspector-v1") { "PostgreSQL inspector policy is not attested" }
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.executeQuery(PostgresInspectorCheck.sql).use { facts ->
                    check(facts.next()) { "PostgreSQL inspector role is unavailable" }
                    val expectedRole = if (host.endsWith(".pooler.supabase.com")) user.substringBeforeLast('.') else user
                    check(facts.getString(1) == expectedRole && (2..8).none(facts::getBoolean)) {
                        "PostgreSQL role failed the least-privilege inspector policy"
                    }
                }
            }
        }
        clock.connected()
        val sql = query?.let {
            if (op.explain) "EXPLAIN (${if (op.analyze) "ANALYZE, BUFFERS, " else ""}FORMAT JSON) $it"
            else "SELECT * FROM ($it) AS reaktor_query LIMIT ${op.maxRows + 1}"
        } ?: "SELECT current_database() AS database, current_user AS role, current_schema() AS schema, count(*) AS tables FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
        return connection.createStatement().use { statement ->
            session.own(statement)
            statement.queryTimeout = (timeout / 1000).toInt().coerceAtLeast(1)
            statement.fetchSize = 100
            statement.maxRows = op.maxRows + 1
            statement.executeQuery(sql).use { rows ->
                clock.executed()
                require(rows.metaData.columnCount <= 512) { "Database result exceeds 512 columns" }
                val columns = (1..rows.metaData.columnCount).map {
                    QueryColumn(rows.metaData.getColumnLabel(it), rows.metaData.getColumnTypeName(it),
                        rows.metaData.isNullable(it).takeUnless { it == java.sql.ResultSetMetaData.columnNullableUnknown }
                            ?.let { it == java.sql.ResultSetMetaData.columnNullable })
                }
                val values = mutableListOf<List<JsonElement>>()
                var bytes = 0
                while (rows.next()) {
                    val row = columns.indices.map { rows.getString(it + 1)?.let(::JsonPrimitive) ?: JsonNull }
                    bytes += row.toString().toByteArray().size
                    require(bytes <= 8_000_000) { "Database result exceeds 8 MiB" }
                    values += row
                }
                val plan = if (op.explain) QueryPlan("postgres-json",
                    Json.parseToJsonElement(values.single().single().jsonPrimitive.content), op.analyze) else null
                QueryReceipt(provider = "Postgres", columns = columns, rows = values,
                    truncated = values.size > op.maxRows, metrics = clock.metrics() + QueryMetric("Cell payload", bytes.toString(), "bytes", QueryMetricSource.Client), plan = plan,
                    warnings = generateSequence(statement.warnings) { it.nextWarning }.take(32).map { "${it.sqlState}: ${it.message}" }.toList())
            }
        }.also { connection.rollback() }
    }

    private fun endpoint(op: InfrastructureOperation.DatabaseRead): Int {
        val target = op.connection as? DatabaseConnection.KubernetesService ?: error("Expected a Kubernetes service connection")
        return KubernetesJvmClient(File(target.kubeconfig), session).portForward(target.namespace, target.service, target.port).localPort
    }

    private fun memgraph(op: InfrastructureOperation.DatabaseRead, query: String?, env: Map<String, String>, timeout: Long): QueryReceipt {
        val clock = QueryClock()
        val read = query?.let {
            val parsed = requireNotNull(MemgraphInspection.parse(it)) { "Memgraph query is outside the registered inspection catalog" }
            require(parsed.limit <= op.maxRows)
            "${parsed.read.cypher}\nSKIP ${parsed.offset} LIMIT ${op.maxRows + 1}"
        } ?: MemgraphInspection.Statistics.statement(1)
        val sql = if (op.explain) "${if (op.analyze) "PROFILE" else "EXPLAIN"} $read" else read
        val auth = env["MEMGRAPH_USER"]?.let { AuthTokens.basic(it, env["MEMGRAPH_PASSWORD"].orEmpty()) } ?: AuthTokens.none()
        val driver = session.own(GraphDatabase.driver("bolt://127.0.0.1:${endpoint(op)}", auth,
            Config.builder().withConnectionTimeout(5, TimeUnit.SECONDS).withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS).build()))
        val db = session.own(driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build()))
        val tx = session.own(db.beginTransaction(TransactionConfig.builder().withTimeout(Duration.ofMillis(timeout)).build()))
        clock.connected()
        val result = tx.run(sql)
        clock.executed()
        val names = result.keys()
        require(names.size <= 512) { "Database result exceeds 512 columns" }
        val values = mutableListOf<List<JsonElement>>()
        var types = names.map { "unknown" }
        var bytes = 0
        while (result.hasNext()) {
            require(values.size <= op.maxRows) { "Memgraph result exceeds row limit" }
            val record = result.next()
            if (values.isEmpty()) types = record.values().map { it.type().name() }
            val row = record.values().map { jsonValue(it.asObject()) }
            bytes += row.toString().toByteArray().size
            require(bytes <= 8_000_000) { "Memgraph result exceeds 8 MiB" }
            values += row
        }
        val summary = result.consume()
        val metrics = clock.metrics() + listOf(
            QueryMetric("Result available", summary.resultAvailableAfter(TimeUnit.MILLISECONDS).toString(), "ms"),
            QueryMetric("Result consumed", summary.resultConsumedAfter(TimeUnit.MILLISECONDS).toString(), "ms"),
            QueryMetric("Cell payload", bytes.toString(), "bytes", QueryMetricSource.Client),
        )
        val plan = if (op.explain) QueryPlan("memgraph-rows", buildJsonObject {
            put("columns", JsonArray(names.map(::JsonPrimitive)))
            put("rows", JsonArray(values.map(::JsonArray)))
        }, op.analyze) else null
        tx.rollback()
        return QueryReceipt(provider = "Memgraph", columns = names.mapIndexed { i, name -> QueryColumn(name, types[i]) },
            rows = values, truncated = values.size > op.maxRows, metrics = metrics, plan = plan)
    }

    private fun clickhouse(op: InfrastructureOperation.DatabaseRead, query: String?, env: Map<String, String>, timeout: Long): QueryReceipt {
        require(!op.analyze) { "ClickHouse reports execution statistics on query results; EXPLAIN ANALYZE is not supported" }
        val clock = QueryClock()
        val sql = query?.let { if (op.explain) "EXPLAIN $it" else "SELECT * FROM ($it) LIMIT ${op.maxRows + 1}" }
            ?: "SELECT currentUser() AS user, currentDatabase() AS database, count() AS tables FROM system.tables WHERE database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')"
        val headers = buildMap {
            env["CLICKHOUSE_USER"]?.let { put("X-ClickHouse-User", it) }
            env["CLICKHOUSE_PASSWORD"]?.let { put("X-ClickHouse-Key", it) }
        }
        val queryId = UUID.randomUUID().toString()
        val uri = URI("http://127.0.0.1:${endpoint(op)}/?readonly=2&query_id=$queryId&max_execution_time=${(timeout / 1000).coerceAtLeast(1)}&max_result_rows=${op.maxRows + 1}&max_result_bytes=8388608&result_overflow_mode=throw")
        clock.connected()
        val response = BoundedHttp(session).response(uri, "$sql FORMAT JSON", headers)
        clock.executed()
        val body = Json.parseToJsonElement(response.body).jsonObject
        val columns = body.getValue("meta").jsonArray.map {
            val column = it.jsonObject
            QueryColumn(column.getValue("name").jsonPrimitive.content, column.getValue("type").jsonPrimitive.content)
        }
        val values = body.getValue("data").jsonArray.map { row -> columns.map { row.jsonObject[it.name] ?: JsonNull } }
        val stats = body["statistics"]?.jsonObject.orEmpty()
        val metrics = clock.metrics() + buildList {
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

}

internal fun checkPrivateFile(file: File, empty: Boolean) {
    require(file.isAbsolute && file.isFile && !Files.isSymbolicLink(file.toPath())) { "Invalid private database session file" }
    if (empty) require(file.length() == 0L) { "Database result destination must be empty" }
    listOf(file, file.parentFile).forEach { path ->
        require(!Files.isSymbolicLink(path.toPath()))
        val permissions = runCatching { Files.getPosixFilePermissions(path.toPath()) }.getOrNull()
        require(permissions == null || permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "Database session path must be owner-only" }
    }
}

internal fun csvRow(values: List<String?>) = values.joinToString(",", postfix = "\n") { value ->
    value?.let { "\"${it.replace("\"", "\"\"")}\"" }.orEmpty()
}

private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> JsonPrimitive(value.toString())
}

private class QueryClock {
    private val started = System.nanoTime()
    private var connectedAt = started
    private var executedAt = started
    fun connected() { connectedAt = System.nanoTime() }
    fun executed() { executedAt = System.nanoTime() }
    fun metrics(): List<QueryMetric> {
        val finished = System.nanoTime()
        fun metric(name: String, duration: Long) = QueryMetric(name,
            String.format(Locale.ROOT, "%.3f", duration.coerceAtLeast(0) / 1_000_000.0), "ms", QueryMetricSource.Client)
        return listOf(metric("Adapter elapsed", finished - started), metric("Connection and session", connectedAt - started),
            metric("Execute and first batch", executedAt - connectedAt), metric("Fetch and decode", finished - executedAt))
    }
}
