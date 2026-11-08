package dev.shibasis.reaktor.cloud.postgres

import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.database.*

class PostgresJvmClient(private val session: InfrastructureSession) {
    fun read(op: QueryOptions, query: String?, env: Map<String, String>, timeout: Long,
        parameters: List<BoundQueryParameter>?): QueryReceipt {
        val clock = QueryClock()
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
        val identity = PostgresConnectionReuse.identityOf(
            host = host,
            port = dataSource.portNumbers.first(),
            database = required("PGDATABASE"),
            user = user,
            password = required("PGPASSWORD"),
            sslMode = env["PGSSLMODE"] ?: "require",
        )
        return PostgresConnectionReuse.withConnection(identity, dataSource) { connection ->
            readOn(connection, op, query, env, timeout, parameters, host, user, clock)
        }
    }

    /**
     * One read on an already-authenticated connection.
     *
     * Split out so the connection's lifetime belongs to [PostgresConnectionReuse] rather than to
     * the session: a session that owned it would close it on the way out and there would be
     * nothing left to reuse. Everything the read verifies is unchanged and still runs per read.
     */
    private fun readOn(
        connection: java.sql.Connection,
        op: QueryOptions,
        query: String?,
        env: Map<String, String>,
        timeout: Long,
        parameters: List<BoundQueryParameter>?,
        host: String,
        user: String,
        clock: QueryClock,
    ): QueryReceipt {
        val policyWarnings = mutableListOf<String>()
        if (query != null) {
            val policy = env["REAKTOR_PG_INSPECTOR_POLICY"]
            require(policy in setOf("least-privilege-inspector-v1", "bounded-read-session")) { "PostgreSQL query access policy is not configured" }
            check(connection.isReadOnly && !connection.autoCommit) { "PostgreSQL queries require a read-only transaction" }
            SqlReadStatement.normalize(query, readOnlyTransaction = true)

            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.executeQuery(PostgresInspectorCheck.sql).use { facts ->
                    check(facts.next()) { "PostgreSQL inspector role is unavailable" }
                    val expectedRole = if (host.endsWith(".pooler.supabase.com")) user.substringBeforeLast('.') else user
                    check(facts.getString(1) == expectedRole && (2..6).none(facts::getBoolean) && !facts.getBoolean(9)) {
                        "PostgreSQL query role has elevated administrative authority"
                    }
                    if (policy == "least-privilege-inspector-v1") {
                        check((7..8).none(facts::getBoolean)) { "PostgreSQL role failed the least-privilege inspector policy" }
                    } else {
                        policyWarnings += "Operator read session: database ACLs include inherited PUBLIC permissions. SQL executes in a read-only transaction; this profile is intended for trusted operators."
                        if (facts.getBoolean(10)) policyWarnings += "The role has effective write privileges outside this read-only transaction."
                        if (facts.getBoolean(11)) policyWarnings += "The role inherits schema CREATE privileges."
                        if (facts.getBoolean(8)) policyWarnings += "The role can execute application or administrative functions."
                    }
                }
            }
        }
        clock.connected()
        val sql = query?.let {
            if (op.explain) "EXPLAIN (${if (op.analyze) "ANALYZE, BUFFERS, " else ""}FORMAT JSON) $it"
            else "SELECT * FROM ($it) AS reaktor_query LIMIT ${op.maxRows + 1}"
        } ?: "SELECT current_database() AS database, current_user AS role, current_schema() AS schema, count(*) AS tables FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
        val executable = if (parameters == null) connection.createStatement() else connection.prepareStatement(sql).also {
            JdbcQueryParameters.bind(it, parameters)
        }
        return executable.use { statement ->
            session.own(statement)
            statement.queryTimeout = (timeout / 1000).toInt().coerceAtLeast(1)
            statement.fetchSize = 100
            statement.maxRows = op.maxRows + 1
            (if (statement is java.sql.PreparedStatement) statement.executeQuery() else statement.executeQuery(sql)).use { rows ->
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
                    warnings = policyWarnings + generateSequence(statement.warnings) { it.nextWarning }.take(32).map { "${it.sqlState}: ${it.message}" }.toList())
            }
        }.also { connection.rollback() }
    }

    fun write(write: WriteStatement, env: Map<String, String>): QueryReceipt {
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
                    writeReceipt("Postgres", columns, rows, write.maxRows, clock.metrics() + writeMetrics(affected, statements), warnings = warnings)
                }
                connection.commit()
                result
            } catch (failure: Exception) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }
}
