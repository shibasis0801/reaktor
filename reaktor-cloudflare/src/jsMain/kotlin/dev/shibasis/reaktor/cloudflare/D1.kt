@file:Suppress("unused")

package dev.shibasis.reaktor.cloudflare

import kotlin.js.ArrayBuffer
import kotlin.js.Promise

/** Metadata returned alongside D1 responses. */
external interface D1Meta {
    val duration: Double
    val size_after: Double
    val rows_read: Double
    val rows_written: Double
    val last_row_id: Double
    val changed_db: Boolean
    val changes: Double
}

/** Successful D1 response payload. */
external interface D1Response {
    val success: Boolean
    val meta: dynamic
    val error: Nothing?
}

/** Structured result set returned by [D1PreparedStatement.all]. */
external interface D1Result<T> : D1Response {
    val results: Array<T>
}

/** Summary returned by [D1Database.exec]. */
external interface D1ExecResult {
    val count: Double
    val duration: Double
}

/** Cloudflare D1 database binding. */
external open class D1Database {
    fun prepare(query: String): D1PreparedStatement
    fun dump(): Promise<ArrayBuffer>
    fun <T> batch(statements: Array<D1PreparedStatement>): Promise<Array<D1Result<T>>>
    fun exec(query: String): Promise<D1ExecResult>
}

/** Prepared statement returned by [D1Database.prepare]. */
external open class D1PreparedStatement {
    fun bind(vararg values: Any?): D1PreparedStatement
    fun <T> first(columnName: String): Promise<T?>
    fun <T> first(): Promise<T?>
    fun run(): Promise<D1Response>
    fun <T> all(): Promise<D1Result<T>>
    fun raw(options: D1RawOptions = definedExternally): Promise<dynamic>
}

/** Options that influence the shape returned by [D1PreparedStatement.raw]. */
external interface D1RawOptions {
    var columnNames: Boolean?
}
