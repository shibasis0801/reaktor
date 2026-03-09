package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

@JsModule("postgres")
@JsNonModule
private external fun postgresFactory(
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

class PostgresRow internal constructor(
    private val raw: dynamic,
) {
    operator fun get(columnName: String): Any? = raw[columnName] as Any?

    fun string(columnName: String): String? = raw[columnName]?.toString()

    fun int(columnName: String): Int? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toIntOrNull()
        }
    }

    fun double(columnName: String): Double? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Number -> value.toDouble()
            else -> value.toString().toDoubleOrNull()
        }
    }

    fun boolean(columnName: String): Boolean? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBooleanStrictOrNull()
        }
    }
}

class PostgresDatabase internal constructor(
    private val client: dynamic,
) {
    suspend fun rows(
        query: String,
        vararg params: Any?,
    ): List<PostgresRow> =
        client.unsafe(query, params.unsafeCast<Array<Any?>>())
            .unsafeCast<Promise<Array<dynamic>>>()
            .await()
            .map(::PostgresRow)

    suspend fun firstOrNull(
        query: String,
        vararg params: Any?,
    ): PostgresRow? = rows(query, *params).firstOrNull()

    suspend fun string(
        query: String,
        columnName: String,
        vararg params: Any?,
    ): String? = firstOrNull(query, *params)?.string(columnName)

    suspend fun close() {
        val result = client.end()
        if (result != null && isPromiseLike(result)) {
            result.unsafeCast<Promise<Any?>>().await()
        }
    }
}

fun Hyperdrive.connectPostgres(
    configure: PostgresOptionsBuilder.() -> Unit = {},
): PostgresDatabase =
    PostgresDatabase(
        postgresFactory(
            connectionString,
            PostgresOptionsBuilder().apply(configure).toJs(),
        ),
    )

fun CloudflareContext.postgres(
    binding: HyperdriveBinding,
    configure: PostgresOptionsBuilder.() -> Unit = {},
): PostgresDatabase = this[binding].connectPostgres(configure)

suspend inline fun <T> CloudflareContext.withPostgres(
    binding: HyperdriveBinding,
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

private fun isPromiseLike(value: dynamic): Boolean = js("value != null && typeof value.then === 'function'") as Boolean
