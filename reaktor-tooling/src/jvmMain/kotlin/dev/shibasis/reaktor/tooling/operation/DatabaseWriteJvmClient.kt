package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.database.*
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.clickhouse.ClickHouseJvmClient
import dev.shibasis.reaktor.cloud.cloudflare.D1JvmClient
import dev.shibasis.reaktor.cloud.database.*
import dev.shibasis.reaktor.cloud.memgraph.MemgraphJvmClient
import dev.shibasis.reaktor.cloud.postgres.PostgresJvmClient

data class DatabaseWrite(
    val engine: DatabaseProvider,
    val connection: DatabaseConnection,
    override val statement: String,
    override val maxRows: Int = 500,
    override val timeoutMillis: Long = 20_000,
) : WriteStatement {
    init {
        require(statement.isNotBlank() && statement.toByteArray().size <= 262_144) { "A write must be between 1 byte and 256 KiB" }
        require(maxRows in 1..500) { "The row limit must be between 1 and 500" }
        require(timeoutMillis in 1_000..20_000) { "The write timeout must be between 1 and 20 seconds" }
    }

    override fun toString() = "DatabaseWrite($engine, statement redacted)"
}

class DatabaseWriteJvmClient(private val session: InfrastructureSession) {
    fun execute(write: DatabaseWrite, environment: Map<String, String>): QueryReceipt = when (write.engine) {
        DatabaseProvider.Postgres -> {
            require(write.connection == DatabaseConnection.PostgresEnvironment) { "Expected the PostgreSQL environment connection" }
            PostgresJvmClient(session).write(write, environment)
        }
        DatabaseProvider.ClickHouse -> ClickHouseJvmClient(session).write(write.connection.kubernetesService(), write, environment)
        DatabaseProvider.Memgraph -> MemgraphJvmClient(session).write(write.connection.kubernetesService(), write, environment)
        DatabaseProvider.D1 -> D1JvmClient(session).write(write.connection.cloudflareD1(), write)
        DatabaseProvider.PubSub, DatabaseProvider.SQLite -> error("${write.engine} has no write adapter")
    }.also { it.validate(write.maxRows, it.provider) }
}
