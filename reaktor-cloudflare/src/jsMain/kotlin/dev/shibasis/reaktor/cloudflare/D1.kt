package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await

class D1Mutation internal constructor(
    private val raw: RawD1Result,
) {
    val success: Boolean
        get() = raw.success == true

    val changedRowCount: Int?
        get() = raw.meta?.changes.asIntOrNull()

    val durationMs: Double?
        get() = raw.meta?.duration.asDoubleOrNull()

    val lastRowId: String?
        get() = raw.meta?.last_row_id?.toString()
}

class D1Database internal constructor(
    private val raw: RawD1Database,
) {
    suspend fun execute(statement: SqlStatement): D1Mutation =
        D1Mutation(prepared(statement).run().await())

    suspend fun execute(
        template: String,
        vararg arguments: Any?,
    ): D1Mutation = execute(d1Query(template, *arguments))

    suspend fun execute(build: SqlBuilder.() -> Unit): D1Mutation =
        execute(d1Query(build))

    suspend fun rawRows(statement: SqlStatement): List<SqlRow> =
        (prepared(statement).all().await().results ?: emptyArray())
            .map(::SqlRow)

    suspend fun rawRows(
        template: String,
        vararg arguments: Any?,
    ): List<SqlRow> = rawRows(d1Query(template, *arguments))

    suspend fun rawRows(build: SqlBuilder.() -> Unit): List<SqlRow> =
        rawRows(d1Query(build))

    suspend inline fun <reified T> rows(statement: SqlStatement): List<T> =
        rawRows(statement).map(SqlRow::decode)

    suspend inline fun <reified T> rows(
        template: String,
        vararg arguments: Any?,
    ): List<T> = rows(d1Query(template, *arguments))

    suspend inline fun <T> rows(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rawRows(statement).map { it.decode(decode) }

    suspend inline fun <T> rows(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(d1Query(template, *arguments), decode)

    suspend inline fun <T> rows(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(d1Query(build), decode)

    suspend fun rawFirstOrNull(statement: SqlStatement): SqlRow? =
        rawRows(statement).firstOrNull()

    suspend fun rawFirstOrNull(
        template: String,
        vararg arguments: Any?,
    ): SqlRow? = rawFirstOrNull(d1Query(template, *arguments))

    suspend fun rawFirstOrNull(build: SqlBuilder.() -> Unit): SqlRow? =
        rawFirstOrNull(d1Query(build))

    suspend inline fun <reified T> firstOrNull(statement: SqlStatement): T? =
        rawFirstOrNull(statement)?.decode()

    suspend inline fun <reified T> firstOrNull(
        template: String,
        vararg arguments: Any?,
    ): T? = firstOrNull(d1Query(template, *arguments))

    suspend inline fun <T> firstOrNull(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = rawFirstOrNull(statement)?.decode(decode)

    suspend inline fun <T> firstOrNull(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(d1Query(template, *arguments), decode)

    suspend inline fun <T> firstOrNull(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(d1Query(build), decode)

    suspend fun string(
        statement: SqlStatement,
        columnName: String,
    ): String? = rawFirstOrNull(statement)?.string(columnName)

    suspend fun string(
        columnName: String,
        template: String,
        vararg arguments: Any?,
    ): String? = string(d1Query(template, *arguments), columnName)

    suspend fun string(
        columnName: String,
        build: SqlBuilder.() -> Unit,
    ): String? = string(d1Query(build), columnName)

    private fun prepared(statement: SqlStatement): RawD1PreparedStatement =
        raw.prepare(statement.query).bind(*statement.params)
}

private fun Any?.asIntOrNull(): Int? =
    when (this) {
        is Number -> toInt()
        null -> null
        else -> toString().toIntOrNull()
    }

private fun Any?.asDoubleOrNull(): Double? =
    when (this) {
        is Number -> toDouble()
        null -> null
        else -> toString().toDoubleOrNull()
    }
