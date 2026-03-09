package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.jsTypeOf

@JsModule("postgres")
@JsNonModule
private external fun postgresModule(
    connectionString: String,
    options: dynamic = definedExternally,
): dynamic

class PostgresOptionsBuilder {
    var max: Int = 1
    var fetchTypes: Boolean = false

    internal fun toJs(): dynamic {
        val options = js("({})")
        options.max = max
        options.fetch_types = fetchTypes
        return options
    }
}

internal class HyperdriveConfig internal constructor(
    private val raw: RawHyperdrive,
) {
    val connectionString: String
        get() = raw.connectionString
}

class PostgresDatabase internal constructor(
    private val client: dynamic,
) {
    suspend fun rawRows(statement: SqlStatement): List<SqlRow> =
        client.unsafe(statement.query, statement.params)
            .unsafeCast<Promise<Array<dynamic>>>()
            .await()
            .map(::SqlRow)

    suspend fun rawRows(
        template: String,
        vararg arguments: Any?,
    ): List<SqlRow> = rawRows(postgresQuery(template, *arguments))

    suspend fun rawRows(build: SqlBuilder.() -> Unit): List<SqlRow> =
        rawRows(postgresQuery(build))

    suspend inline fun <reified T> rows(statement: SqlStatement): List<T> =
        rawRows(statement).map(SqlRow::decode)

    suspend inline fun <reified T> rows(
        template: String,
        vararg arguments: Any?,
    ): List<T> = rows(postgresQuery(template, *arguments))

    suspend inline fun <T> rows(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rawRows(statement).map { it.decode(decode) }

    suspend inline fun <T> rows(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(postgresQuery(template, *arguments), decode)

    suspend inline fun <T> rows(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(postgresQuery(build), decode)

    suspend fun rawFirstOrNull(statement: SqlStatement): SqlRow? =
        rawRows(statement).firstOrNull()

    suspend fun rawFirstOrNull(
        template: String,
        vararg arguments: Any?,
    ): SqlRow? = rawFirstOrNull(postgresQuery(template, *arguments))

    suspend fun rawFirstOrNull(build: SqlBuilder.() -> Unit): SqlRow? =
        rawFirstOrNull(postgresQuery(build))

    suspend inline fun <reified T> firstOrNull(statement: SqlStatement): T? =
        rawFirstOrNull(statement)?.decode()

    suspend inline fun <reified T> firstOrNull(
        template: String,
        vararg arguments: Any?,
    ): T? = firstOrNull(postgresQuery(template, *arguments))

    suspend inline fun <T> firstOrNull(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = rawFirstOrNull(statement)?.decode(decode)

    suspend inline fun <T> firstOrNull(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(postgresQuery(template, *arguments), decode)

    suspend inline fun <T> firstOrNull(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(postgresQuery(build), decode)

    suspend fun string(
        statement: SqlStatement,
        columnName: String,
    ): String? = rawFirstOrNull(statement)?.string(columnName)

    suspend fun string(
        columnName: String,
        template: String,
        vararg arguments: Any?,
    ): String? = string(postgresQuery(template, *arguments), columnName)

    suspend fun string(
        columnName: String,
        build: SqlBuilder.() -> Unit,
    ): String? = string(postgresQuery(build), columnName)

    suspend fun close() {
        val result = client.end()
        if (result != null && isPromiseLike(result)) {
            result.unsafeCast<Promise<Any?>>().await()
        }
    }
}

internal fun HyperdriveConfig.connectPostgres(
    configure: PostgresOptionsBuilder.() -> Unit = {},
): PostgresDatabase =
    PostgresDatabase(
        postgresModule(
            connectionString,
            PostgresOptionsBuilder().apply(configure).toJs(),
        ),
    )

class PostgresBinding internal constructor(
    internal val name: String,
    internal val configure: PostgresOptionsBuilder.() -> Unit = {},
)

fun postgres(
    name: String,
    configure: PostgresOptionsBuilder.() -> Unit = {},
): PostgresBinding = PostgresBinding(name, configure)

fun CloudflareContext.postgres(
    binding: PostgresBinding,
    configure: PostgresOptionsBuilder.() -> Unit = {},
): PostgresDatabase =
    requireHyperdrive(binding.name).connectPostgres {
        binding.configure(this)
        configure()
    }

suspend inline fun <T> CloudflareContext.postgres(
    binding: PostgresBinding,
    noinline configure: PostgresOptionsBuilder.() -> Unit = {},
    block: suspend PostgresDatabase.() -> T,
): T {
    val database = postgres(binding, configure)
    try {
        return database.block()
    } finally {
        database.close()
    }
}

suspend inline fun <T> CloudflareRequest.postgres(
    binding: PostgresBinding,
    noinline configure: PostgresOptionsBuilder.() -> Unit = {},
    block: suspend PostgresDatabase.() -> T,
): T = context.postgres(binding, configure, block)

private fun isPromiseLike(value: dynamic): Boolean =
    value != null && jsTypeOf(value.then) == "function"
