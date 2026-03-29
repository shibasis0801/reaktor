package dev.shibasis.reaktor.db.graph

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.future.await
import org.neo4j.driver.AuthToken
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Record
import org.neo4j.driver.async.AsyncSession
import org.neo4j.driver.summary.ResultSummary

interface GraphDb: AutoCloseable {
    fun tenant(tenantId: String): TenantGraphDb
}

interface TenantGraphDb {
    val tenantId: String

    suspend fun records(
        cypher: String,
        parameters: Map<String, Any?> = emptyMap(),
    ): List<Record>

    suspend fun execute(
        cypher: String,
        parameters: Map<String, Any?> = emptyMap(),
    ): ResultSummary

    suspend fun <T> list(
        cypher: String,
        parameters: Map<String, Any?> = emptyMap(),
        transform: (Record) -> T,
    ): List<T> = records(cypher, parameters).map(transform)

    suspend fun <T> one(
        cypher: String,
        parameters: Map<String, Any?> = emptyMap(),
        transform: (Record) -> T,
    ): T? = records(cypher, parameters).singleOrNull()?.let(transform)
}

class Neo4jGraphDb(
    private val driver: Driver,
    private val policy: TenantGraphQueryPolicy = MandatoryTenantParameterization(),
): GraphDb {
    override fun tenant(tenantId: String): TenantGraphDb = Neo4jTenantGraphDb(driver, tenantId, policy)

    override fun close() {
        driver.close()
    }
}

private class Neo4jTenantGraphDb(
    private val driver: Driver,
    override val tenantId: String,
    private val policy: TenantGraphQueryPolicy,
): TenantGraphDb {
    override suspend fun records(
        cypher: String,
        parameters: Map<String, Any?>,
    ): List<Record> = withSession { session ->
        val query = policy.bind(tenantId, TenantGraphQuery(cypher, parameters))
        session.runAsync(query.cypher, query.parameters.toMutableMap()).await().listAsync().await()
    }

    override suspend fun execute(
        cypher: String,
        parameters: Map<String, Any?>,
    ): ResultSummary = withSession { session ->
        val query = policy.bind(tenantId, TenantGraphQuery(cypher, parameters))
        session.runAsync(query.cypher, query.parameters.toMutableMap()).await().consumeAsync().await()
    }

    private suspend fun <T> withSession(block: suspend (AsyncSession) -> T): T {
        val session = driver.session(AsyncSession::class.java)
        return try {
            block(session)
        } finally {
            session.closeAsync().await()
        }
    }
}

fun memgraphDriver(
    host: String = "localhost",
    port: String = "7687",
    auth: AuthToken = AuthTokens.none(),
): Driver = GraphDatabase.driver("bolt://$host:$port", auth)

var Feature.GraphDb by CreateSlot<GraphDb>()
