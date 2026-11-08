package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.database.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.clickhouse.ClickHouseJvmClient
import dev.shibasis.reaktor.cloud.cloudflare.D1JvmClient
import dev.shibasis.reaktor.cloud.database.*
import dev.shibasis.reaktor.cloud.google.PubSubJvmClient
import dev.shibasis.reaktor.cloud.google.PubSubQuery
import dev.shibasis.reaktor.cloud.http.ProviderHttpFailure
import dev.shibasis.reaktor.cloud.memgraph.MemgraphJvmClient
import dev.shibasis.reaktor.cloud.postgres.PostgresJvmClient

class DatabaseJvmClient(private val session: InfrastructureSession) {
    fun execute(op: InfrastructureOperation.DatabaseRead, environment: Map<String, String>, timeoutMillis: Long): String {
        require(op.maxRows in 1..500)
        val payload = op.queryFile?.let { path ->
            val file = File(path)
            checkPrivateFile(file, false)
            require(file.length() in 1..262_144) { "Invalid query size" }
            file.readText()
        }
        require(!op.parameterized || (op.engine == DatabaseProvider.Postgres && payload != null)) { "Bound parameters require the PostgreSQL JVM adapter" }
        val bound = if (op.parameterized) Json.decodeFromString<BoundQuery>(requireNotNull(payload)).also { it.validate(readOnlyTransaction = op.engine == DatabaseProvider.Postgres) } else null
        val query = bound?.statement ?: payload
        val resultFile = op.resultFile?.let(::File)
        require((query == null) == (resultFile == null)) { "Query requires a private result destination" }
        resultFile?.let { checkPrivateFile(it, true) }
        val receipt = try { when (op.engine) {
            DatabaseProvider.PubSub -> {
                require(!op.explain && !op.analyze)
                PubSubJvmClient(session).read(op.connection as DatabaseConnection.GoogleProject,
                    query?.let { Json.decodeFromString<PubSubQuery>(it) } ?: PubSubQuery(), op.maxRows)
            }
            DatabaseProvider.Postgres -> {
                require(op.connection == DatabaseConnection.PostgresEnvironment)
                PostgresJvmClient(session).read(op, query, environment, timeoutMillis, bound?.parameters)
            }
            DatabaseProvider.Memgraph -> MemgraphJvmClient(session).read(op.connection.kubernetesService(), op, query, environment, timeoutMillis)
            DatabaseProvider.ClickHouse -> ClickHouseJvmClient(session).read(op.connection.kubernetesService(), op, query, environment, timeoutMillis)
            DatabaseProvider.D1 -> D1JvmClient(session).read(op.connection.cloudflareD1(), op, query)
            DatabaseProvider.SQLite -> SQLiteJvmClient(session).read(op, query, timeoutMillis)
        } } catch (failure: Exception) {
            if (query == null || resultFile == null || op.resultFormat != DatabaseResultFormat.QueryReceipt) throw failure
            val detail = when (failure) {
                is QueryParameterException -> QueryError("parameter_validation", failure.message.orEmpty())
                is org.postgresql.util.PSQLException -> QueryError(failure.sqlState ?: "postgres_error", failure.serverErrorMessage?.message ?: "PostgreSQL query failed", failure.serverErrorMessage?.position?.takeIf { it > 0 })
                is org.neo4j.driver.exceptions.Neo4jException -> QueryError(failure.code(), failure.message.orEmpty().take(4096))
                is ProviderHttpFailure -> QueryError("http_${failure.status}", failure.privateBody.take(4096))
                is java.sql.SQLException -> QueryError("sqlite_${failure.errorCode}", failure.message.orEmpty().take(4096))
                else -> throw failure
            }
            QueryReceipt(provider = op.engine.name, columns = emptyList(), rows = emptyList(), error = detail.copy(message =
                environment.filterKeys { it.contains("PASSWORD") || it.contains("SECRET") || it.contains("TOKEN") }.values.filter(String::isNotBlank)
                    .fold(detail.message) { text, secret -> text.replace(secret, "[redacted]") }))
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
        check(receipt.error == null) { "Database query failed; diagnostics are in the private session channel" }
        return "Completed native ${op.engine} read; result is in the private session channel"
    }
}

internal fun DatabaseConnection.kubernetesService() =
    this as? DatabaseConnection.KubernetesService ?: error("Expected a Kubernetes service connection")

internal fun DatabaseConnection.cloudflareD1() =
    this as? DatabaseConnection.CloudflareD1 ?: error("Expected a Cloudflare D1 connection")

private fun csvRow(values: List<String?>) = values.joinToString(",", postfix = "\n") { value ->
    value?.let { "\"${it.replace("\"", "\"\"")}\"" }.orEmpty()
}
