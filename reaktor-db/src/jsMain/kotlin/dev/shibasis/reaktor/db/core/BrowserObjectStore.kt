@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.db.core

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.js.Promise

/** The promise facade for React consumers of the same SQLDelight/OPFS object database. */
@JsExport
class BrowserObjectStore internal constructor(private val database: SqliteObjectDatabase) {
    private val scope = MainScope()
    fun get(store: String, key: String): Promise<String?> = scope.promise {
        database.get(store, key, JsonElement::class, JsonElement.serializer())?.value?.toString()
    }
    fun put(store: String, key: String, json: String): Promise<Unit> = scope.promise {
        database.put(store, key, Json.parseToJsonElement(json), JsonElement.serializer())
        Unit
    }
    fun clear(store: String): Promise<Unit> = scope.promise { database.clear(store) }
}

@JsExport
fun openBrowserObjectStore(name: String): Promise<BrowserObjectStore> = MainScope().promise {
    BrowserObjectStore(openWebSqliteObjectDatabase(name))
}
