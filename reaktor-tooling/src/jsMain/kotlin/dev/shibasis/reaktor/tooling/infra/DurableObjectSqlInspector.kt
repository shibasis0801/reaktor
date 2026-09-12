@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.js.JsExport

/** Binding-only adapter. The owning Worker authenticates the caller and supplies its own storage. */
@JsExport
class DurableObjectSqlInspector(private val storage: Any) {
    fun read(payload: String, maxRows: Int): String {
        require(payload.encodeToByteArray().size <= 262_144 && maxRows in 1..500)
        val request = Json.decodeFromString<StoreKeyQuery>(payload)
        request.validate("DurableObjects")
        require(request.action != DurableObjectReadAction.Overview)
        val database = storage.asDynamic().sql
        require(database != null) { "This instance uses KV-backed storage and has no SQL engine" }
        val sql = if (request.action == DurableObjectReadAction.Tables)
            "SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view','index','trigger') AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' ORDER BY type,name"
        else SqlReadStatement.normalize(requireNotNull(request.sql))
        val bounded = if (request.explain) "EXPLAIN QUERY PLAN $sql" else "SELECT * FROM ($sql) AS reaktor_read LIMIT ${maxRows + 1}"
        val started = js("Date.now()") as Double
        val receipt = try {
            val cursor = database.exec(bounded)
            val names = cursor.columnNames.unsafeCast<Array<String>>()
            require(names.size <= 512)
            val iterator = cursor.raw()
            val rows = mutableListOf<List<JsonElement>>()
            var bytes = 0
            while (true) {
                val next = iterator.next()
                if (next.done == true) break
                require(rows.size <= maxRows)
                val values = next.value.unsafeCast<Array<dynamic>>().map { value ->
                    val json = if (value != null && js("value instanceof ArrayBuffer") as Boolean) {
                        val view = js("new Int8Array(value)")
                        val data = ByteArray(view.length as Int) { (view[it] as Int).toByte() }
                        buildJsonObject { put("encoding", "base64"); put("value", kotlin.io.encoding.Base64.Default.encode(data)) }.toString()
                    } else JSON.stringify(value)
                    Json.parseToJsonElement(json ?: "null")
                }
                bytes += values.toString().encodeToByteArray().size
                require(bytes <= 8_000_000) { "Instance query exceeds 8 MiB" }
                rows += values
            }
            QueryReceipt(provider = "DurableObjects", columns = names.map { QueryColumn(it, "dynamic") }, rows = rows,
                truncated = rows.size > maxRows,
                metrics = listOf(QueryMetric("Rows read", cursor.rowsRead.toString(), "rows"),
                    QueryMetric("Rows written", cursor.rowsWritten.toString(), "rows"),
                    QueryMetric("Database size", database.databaseSize.toString(), "bytes"),
                    QueryMetric("Server execution", ((js("Date.now()") as Double) - started).toString(), "ms")),
                plan = if (request.explain) QueryPlan("sqlite-query-plan", JsonArray(rows.map { row -> JsonObject(names.mapIndexed { i, name -> name to row[i] }.toMap()) })) else null)
        } catch (error: Throwable) {
            QueryReceipt(provider = "DurableObjects", columns = emptyList(), rows = emptyList(), error = QueryError("instance_sql", error.message.orEmpty().take(4096)))
        }
        receipt.validate(maxRows, "DurableObjects")
        return Json.encodeToString(receipt).also { require(it.encodeToByteArray().size <= 8_388_608) }
    }
}
