package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.cloud.CloudflareLogin
import dev.shibasis.reaktor.tooling.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.neo4j.driver.AccessMode
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.TransactionConfig
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DatabaseWrite(
    val engine: DatabaseProvider,
    val connection: DatabaseConnection,
    val statement: String,
    val maxRows: Int = 500,
    val timeoutMillis: Long = 20_000,
) {
    init {
        require(statement.isNotBlank() && statement.toByteArray().size <= 262_144) { "A write must be between 1 byte and 256 KiB" }
        require(maxRows in 1..500) { "The row limit must be between 1 and 500" }
        require(timeoutMillis in 1_000..20_000) { "The write timeout must be between 1 and 20 seconds" }
    }

    override fun toString() = "DatabaseWrite($engine, statement redacted)"
}

class DatabaseWriteJvmClient(private val session: InfrastructureSession) {
    fun execute(write: DatabaseWrite, environment: Map<String, String>): QueryReceipt = when (write.engine) {
        DatabaseProvider.Postgres -> postgres(write, environment)
        DatabaseProvider.ClickHouse -> clickhouse(write, environment)
        DatabaseProvider.Memgraph -> memgraph(write, environment)
        DatabaseProvider.D1 -> d1(write)
        DatabaseProvider.PubSub, DatabaseProvider.SQLite -> error("${write.engine} has no write adapter")
    }.also { it.validate(write.maxRows, it.provider) }

    private fun postgres(write: DatabaseWrite, env: Map<String, String>): QueryReceipt {
        require(write.connection == DatabaseConnection.PostgresEnvironment) { "Expected the PostgreSQL environment connection" }
        fun required(key: String) = requireNotNull(env[key]?.takeIf(String::isNotBlank)) { "Missing PostgreSQL writer field $key" }
        val clock = QueryClock()
        val source = PGSimpleDataSource().apply {
            serverNames = arrayOf(required("PGHOST"))
            portNumbers = intArrayOf(required("PGPORT").toInt())
            databaseName = required("PGDATABASE")
            user = required("PGUSER")
            password = required("PGPASSWORD")
            sslMode = env["PGSSLMODE"] ?: "require"
            connectTimeout = 5
            socketTimeout = (write.timeoutMillis / 1000 + 5).toInt()
            prepareThreshold = 0
            applicationName = "reaktor-kernel-write"
        }
        val connection = session.own(source.connection)
        return connection.use {
            connection.autoCommit = false
            clock.connected()
            try {
                connection.createStatement().use { it.execute("SET LOCAL statement_timeout = ${write.timeoutMillis}") }
                val statement = session.own(connection.createStatement())
                val result = statement.use {
                    statement.queryTimeout = (write.timeoutMillis / 1000).toInt()
                    statement.maxRows = write.maxRows + 1
                    var columns = emptyList<QueryColumn>()
                    var rows = emptyList<List<JsonElement>>()
                    var affected = 0L
                    var statements = 0
                    var producedRows = statement.execute(write.statement)
                    clock.executed()
                    while (true) {
                        if (producedRows) statement.resultSet.use { set ->
                            columns = (1..set.metaData.columnCount).map { QueryColumn(set.metaData.getColumnLabel(it), set.metaData.getColumnTypeName(it)) }
                            rows = buildList { while (set.next()) add(columns.indices.map { set.getString(it + 1)?.let(::JsonPrimitive) ?: JsonNull }) }
                        } else {
                            val count = statement.largeUpdateCount
                            if (count < 0) break
                            affected += count
                        }
                        statements++
                        producedRows = statement.moreResults
                    }
                    val warnings = generateSequence(statement.warnings) { it.nextWarning }.take(32).map { "${it.sqlState}: ${it.message}" }.toList()
                    receipt("Postgres", columns, rows, write.maxRows, clock.metrics() + written(affected, statements), warnings = warnings)
                }
                connection.commit()
                result
            } catch (failure: Exception) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }

    private fun clickhouse(write: DatabaseWrite, env: Map<String, String>): QueryReceipt {
        val clock = QueryClock()
        val headers = buildMap {
            env["CLICKHOUSE_USER"]?.let { put("X-ClickHouse-User", it) }
            env["CLICKHOUSE_PASSWORD"]?.let { put("X-ClickHouse-Key", it) }
        }
        val queryId = UUID.randomUUID().toString()
        val uri = URI("http://127.0.0.1:${endpoint(write.connection)}/?query_id=$queryId&wait_end_of_query=1" +
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
        return if (text.isEmpty()) receipt("ClickHouse", columns, rows, write.maxRows, metrics, response.header("X-ClickHouse-Query-Id") ?: queryId)
        else receipt("ClickHouse", listOf(QueryColumn("result", "String")), text.map { listOf(JsonPrimitive(it)) }, write.maxRows, metrics, queryId)
    }

    private fun memgraph(write: DatabaseWrite, env: Map<String, String>): QueryReceipt {
        val clock = QueryClock()
        val auth = env["MEMGRAPH_USER"]?.let { AuthTokens.basic(it, env["MEMGRAPH_PASSWORD"].orEmpty()) } ?: AuthTokens.none()
        val driver = session.own(GraphDatabase.driver("bolt://127.0.0.1:${endpoint(write.connection)}", auth,
            Config.builder().withConnectionTimeout(5, TimeUnit.SECONDS).withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS).build()))
        val graph = session.own(driver.session(SessionConfig.builder().withDefaultAccessMode(AccessMode.WRITE).build()))
        val transaction = session.own(graph.beginTransaction(TransactionConfig.builder().withTimeout(Duration.ofMillis(write.timeoutMillis)).build()))
        clock.connected()
        val result = transaction.run(write.statement)
        clock.executed()
        val names = result.keys()
        val rows = mutableListOf<List<JsonElement>>()
        var types = names.map { "unknown" }
        while (result.hasNext() && rows.size <= write.maxRows) {
            val record = result.next()
            if (rows.isEmpty()) types = record.values().map { it.type().name() }
            rows += record.values().map { graphValue(it.asObject()) }
        }
        val counters = result.consume().counters()
        transaction.commit()
        val changed = listOf(
            "Nodes created" to counters.nodesCreated(), "Nodes deleted" to counters.nodesDeleted(),
            "Relationships created" to counters.relationshipsCreated(), "Relationships deleted" to counters.relationshipsDeleted(),
            "Properties set" to counters.propertiesSet(), "Labels added" to counters.labelsAdded(), "Labels removed" to counters.labelsRemoved(),
        ).filter { it.second > 0 }.map { (name, count) -> QueryMetric(name, count.toString()) }
        return receipt("Memgraph", names.mapIndexed { index, name -> QueryColumn(name, types[index]) }, rows, write.maxRows, clock.metrics() + changed)
    }

    private fun d1(write: DatabaseWrite): QueryReceipt {
        val connection = write.connection as? DatabaseConnection.CloudflareD1 ?: error("Expected a Cloudflare D1 connection")
        require(connection.account.matches(Regex("[0-9a-f]{32}"))) { "Invalid Cloudflare account id" }
        require(connection.databaseId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid D1 database id" }
        val clock = QueryClock()
        val uri = URI("https://api.cloudflare.com/client/v4/accounts/${connection.account}/d1/database/${connection.databaseId}/raw")
        val login = CloudflareLogin.forWorkspace(File(connection.workspace))
        val http = BoundedHttp(session)
        val payload = buildJsonObject { put("sql", write.statement) }.toString()
        clock.connected()
        val response = runBlocking {
            login.authenticated({ token ->
                runCatching { http.response(uri, payload, mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")) }
            }, { (it.exceptionOrNull() as? ProviderHttpFailure)?.status == 401 })
        }.getOrThrow()
        clock.executed()
        val body = Json.parseToJsonElement(response.body).jsonObject
        check(body["success"]?.jsonPrimitive?.booleanOrNull == true) { "D1 reported an unsuccessful write" }
        val statements = body["result"]?.jsonArray.orEmpty().map { it.jsonObject }
        check(statements.isNotEmpty()) { "D1 returned no statement result" }
        val last = statements.last()["results"]?.jsonObject
        val columns = last?.get("columns")?.jsonArray.orEmpty().map { QueryColumn(it.jsonPrimitive.content, "unknown") }
        val rows = last?.get("rows")?.jsonArray.orEmpty().map { it.jsonArray.toList() }
        val changed = statements.sumOf { it["meta"]?.jsonObject?.get("changes")?.jsonPrimitive?.longOrNull ?: 0 }
        val written = statements.sumOf { it["meta"]?.jsonObject?.get("rows_written")?.jsonPrimitive?.longOrNull ?: 0 }
        return receipt(DatabaseProvider.D1.name, columns, rows, write.maxRows,
            clock.metrics(http = true) + written(changed, statements.size) + QueryMetric("Rows written", written.toString(), "rows"))
    }

    private fun endpoint(connection: DatabaseConnection): Int {
        val target = connection as? DatabaseConnection.KubernetesService ?: error("Expected a Kubernetes service connection")
        return KubernetesJvmClient(File(target.kubeconfig), session).portForward(target.namespace, target.service, target.port).localPort
    }

    private fun written(affected: Long, statements: Int) = listOf(
        QueryMetric("Rows affected", affected.toString(), "rows"), QueryMetric("Statements", statements.toString()))

    private fun receipt(provider: String, columns: List<QueryColumn>, rows: List<List<JsonElement>>, maxRows: Int,
        metrics: List<QueryMetric>, queryId: String? = null, warnings: List<String> = emptyList()) =
        QueryReceipt(provider = provider, columns = columns, rows = rows.take(maxRows), truncated = rows.size > maxRows,
            metrics = metrics, queryId = queryId, warnings = warnings)
}

private fun graphValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to graphValue(it.value) })
    is Iterable<*> -> JsonArray(value.map(::graphValue))
    else -> JsonPrimitive(value.toString())
}
