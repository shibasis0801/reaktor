package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.JsExport
import kotlin.js.Promise

@JsExport
class D1Mutation internal constructor(
    private val raw: RawD1Result,
) {
    val success: Boolean
        get() = raw.success == true

    val changedRowCount: Int?
        get() {
            val changes: Any? = raw.meta?.changes
            return changes.asIntOrNull()
        }

    val durationMs: Double?
        get() {
            val duration: Any? = raw.meta?.duration
            return duration.asDoubleOrNull()
        }

    val lastRowId: String?
        get() = raw.meta?.last_row_id?.toString()
}

@JsExport
class D1Database internal constructor(
    private val raw: RawD1Database,
) {
    @JsExport.Ignore
    suspend fun execute(statement: SqlStatement): D1Mutation =
        D1Mutation(prepared(statement).run().await())

    @JsExport.Ignore
    suspend fun execute(
        template: String,
        vararg arguments: Any?,
    ): D1Mutation = execute(d1Query(template, *arguments))

    fun executeAsync(
        template: String,
        arguments: Array<out Any?> = emptyArray(),
    ): Promise<D1Mutation> = promiseOf {
        execute(template, *arguments)
    }

    @JsExport.Ignore
    suspend fun execute(build: SqlBuilder.() -> Unit): D1Mutation =
        execute(d1Query(build))

    @JsExport.Ignore
    suspend fun rawRows(statement: SqlStatement): List<SqlRow> =
        (prepared(statement).all().await().results ?: emptyArray())
            .map(::SqlRow)

    @JsExport.Ignore
    suspend fun rawRows(
        template: String,
        vararg arguments: Any?,
    ): List<SqlRow> = rawRows(d1Query(template, *arguments))

    fun rawRowsAsync(
        template: String,
        arguments: Array<out Any?> = emptyArray(),
    ): Promise<Array<SqlRow>> = promiseOf {
        rawRows(template, *arguments).toTypedArray()
    }

    @JsExport.Ignore
    suspend fun rawRows(build: SqlBuilder.() -> Unit): List<SqlRow> =
        rawRows(d1Query(build))

    @JsExport.Ignore
    suspend inline fun <reified T> rows(statement: SqlStatement): List<T> =
        rawRows(statement).map(SqlRow::decode)

    @JsExport.Ignore
    suspend inline fun <reified T> rows(
        template: String,
        vararg arguments: Any?,
    ): List<T> = rows(d1Query(template, *arguments))

    @JsExport.Ignore
    suspend inline fun <T> rows(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rawRows(statement).map { it.decode(decode) }

    @JsExport.Ignore
    suspend inline fun <T> rows(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(d1Query(template, *arguments), decode)

    @JsExport.Ignore
    suspend inline fun <T> rows(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): List<T> = rows(d1Query(build), decode)

    @JsExport.Ignore
    suspend fun rawFirstOrNull(statement: SqlStatement): SqlRow? =
        rawRows(statement).firstOrNull()

    @JsExport.Ignore
    suspend fun rawFirstOrNull(
        template: String,
        vararg arguments: Any?,
    ): SqlRow? = rawFirstOrNull(d1Query(template, *arguments))

    fun rawFirstOrNullAsync(
        template: String,
        arguments: Array<out Any?> = emptyArray(),
    ): Promise<SqlRow?> = promiseOf {
        rawFirstOrNull(template, *arguments)
    }

    @JsExport.Ignore
    suspend fun rawFirstOrNull(build: SqlBuilder.() -> Unit): SqlRow? =
        rawFirstOrNull(d1Query(build))

    @JsExport.Ignore
    suspend inline fun <reified T> firstOrNull(statement: SqlStatement): T? =
        rawFirstOrNull(statement)?.decode()

    @JsExport.Ignore
    suspend inline fun <reified T> firstOrNull(
        template: String,
        vararg arguments: Any?,
    ): T? = firstOrNull(d1Query(template, *arguments))

    @JsExport.Ignore
    suspend inline fun <T> firstOrNull(
        statement: SqlStatement,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = rawFirstOrNull(statement)?.decode(decode)

    @JsExport.Ignore
    suspend inline fun <T> firstOrNull(
        template: String,
        vararg arguments: Any?,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(d1Query(template, *arguments), decode)

    @JsExport.Ignore
    suspend inline fun <T> firstOrNull(
        noinline build: SqlBuilder.() -> Unit,
        noinline decode: SqlRowDecoder.() -> T,
    ): T? = firstOrNull(d1Query(build), decode)

    @JsExport.Ignore
    suspend fun string(
        statement: SqlStatement,
        columnName: String,
    ): String? = rawFirstOrNull(statement)?.string(columnName)

    @JsExport.Ignore
    suspend fun string(
        columnName: String,
        template: String,
        vararg arguments: Any?,
    ): String? = string(d1Query(template, *arguments), columnName)

    fun stringAsync(
        columnName: String,
        template: String,
        arguments: Array<out Any?> = emptyArray(),
    ): Promise<String?> = promiseOf {
        string(columnName, template, *arguments)
    }

    @JsExport.Ignore
    suspend fun string(
        columnName: String,
        build: SqlBuilder.() -> Unit,
    ): String? = string(d1Query(build), columnName)

    private fun prepared(statement: SqlStatement): RawD1PreparedStatement =
        raw.prepare(statement.query).bind(*statement.params.map(::bindable).toTypedArray())
}

/**
 * A value D1 will accept as a bind parameter.
 *
 * Modules compiled with `-Xes-long-as-bigint` — which every Worker here is, so that `Long` has the
 * same representation across the whole bundle — turn Kotlin `Long` into a JS `bigint`, and D1's
 * binder rejects those outright with `D1_TYPE_ERROR: Type 'bigint' not supported`. Every timestamp,
 * size and counter in a query hits it, so the conversion belongs here rather than at each call site.
 *
 * Converted through `Number`, which is exact to 2^53. Timestamps in milliseconds are around 1.8e12
 * and sizes are smaller still, so nothing Cairn stores comes close; a genuinely 64-bit identifier
 * would need to be bound as text instead, and should be.
 */
private fun bindable(value: Any?): Any? =
    if (jsTypeOf(value) == "bigint") numberOf(value) else value

private fun numberOf(value: dynamic): Double = js("Number(value)").unsafeCast<Double>()

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
