package dev.shibasis.reaktor.cloud.memgraph

import kotlinx.serialization.json.*
import org.neo4j.driver.AccessMode
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.TransactionConfig
import java.time.Duration
import java.util.concurrent.TimeUnit
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.database.*
import dev.shibasis.reaktor.cloud.kubernetes.ClusterService
import dev.shibasis.reaktor.cloud.kubernetes.localPort

class MemgraphJvmClient(private val session: InfrastructureSession) {
    fun read(service: ClusterService, op: QueryOptions, query: String?, env: Map<String, String>, timeout: Long): QueryReceipt {
        val clock = QueryClock()
        var pageLimit = op.maxRows
        val read = query?.let {
            val parsed = requireNotNull(MemgraphInspection.parse(it)) { "Memgraph query is outside the registered inspection catalog" }
            require(parsed.limit <= op.maxRows)
            pageLimit = parsed.limit
            "${parsed.cypher}\nSKIP ${parsed.offset} LIMIT ${parsed.limit + 1}"
        } ?: MemgraphInspection.Statistics.statement(1)
        val sql = if (op.explain) "${if (op.analyze) "PROFILE" else "EXPLAIN"} $read" else read
        val auth = env["MEMGRAPH_USER"]?.let { AuthTokens.basic(it, env["MEMGRAPH_PASSWORD"].orEmpty()) } ?: AuthTokens.none()
        val driver = session.own(GraphDatabase.driver("bolt://127.0.0.1:${service.localPort(session)}", auth,
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
            rows = values.take(pageLimit), truncated = values.size > pageLimit, metrics = metrics, plan = plan)
    }

    fun write(service: ClusterService, write: WriteStatement, env: Map<String, String>): QueryReceipt {
        val clock = QueryClock()
        val auth = env["MEMGRAPH_USER"]?.let { AuthTokens.basic(it, env["MEMGRAPH_PASSWORD"].orEmpty()) } ?: AuthTokens.none()
        val driver = session.own(GraphDatabase.driver("bolt://127.0.0.1:${service.localPort(session)}", auth,
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
            rows += record.values().map { jsonValue(it.asObject()) }
        }
        val counters = result.consume().counters()
        transaction.commit()
        val changed = listOf(
            "Nodes created" to counters.nodesCreated(), "Nodes deleted" to counters.nodesDeleted(),
            "Relationships created" to counters.relationshipsCreated(), "Relationships deleted" to counters.relationshipsDeleted(),
            "Properties set" to counters.propertiesSet(), "Labels added" to counters.labelsAdded(), "Labels removed" to counters.labelsRemoved(),
        ).filter { it.second > 0 }.map { (name, count) -> QueryMetric(name, count.toString()) }
        return writeReceipt("Memgraph", names.mapIndexed { index, name -> QueryColumn(name, types[index]) }, rows, write.maxRows, clock.metrics() + changed)
    }
}

private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> JsonPrimitive(value.toString())
}
