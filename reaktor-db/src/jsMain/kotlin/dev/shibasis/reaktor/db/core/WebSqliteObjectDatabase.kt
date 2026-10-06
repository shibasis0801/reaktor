package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import dev.shibasis.reaktor.db.RawObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Worker
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/** Opens the shared SQLDelight database through the shipped SQLite/Wasm worker and OPFS. */
suspend fun openWebSqliteObjectDatabase(name: String): SqliteObjectDatabase {
    require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid database name" }
    val worker = js("new Worker('/reaktor-sqlite-worker.mjs?name=' + encodeURIComponent(name), {type: 'module'})")
        .unsafeCast<Worker>()
    val driver = WebWorkerDriver(worker)
    try {
        val database = SqliteObjectDatabase(driver, name)
        database.ready()
        migrateIndexedDb(name, driver, database)
        return database
    } catch (failure: Throwable) {
        driver.close()
        throw failure
    }
}

private suspend fun migrateIndexedDb(name: String, driver: SqlDriver, database: SqliteObjectDatabase) {
    val factory = js("globalThis.indexedDB") ?: return
    val locks = js("globalThis.navigator.locks")
    val scope = CoroutineScope(currentCoroutineContext())
    val lock = "reaktor-sqlite-migration-$name"
    locks.request(lock, {
        scope.promise {
            // Keep the marker outside object stores: clearing app data must not restore old sessions.
            driver.execute(null, "CREATE TABLE IF NOT EXISTS reaktor_web_migrations (name TEXT PRIMARY KEY)", 0).await()
            val migrated = driver.executeQuery(null,
                "SELECT name FROM reaktor_web_migrations WHERE name = 'indexeddb-v1'",
                { QueryResult.Value(it.next().value) }, 0,
            ).await()
            if (!migrated) {
                val legacy = readLegacyRows(factory, "reaktor-objects-$name")
                val existing = database.exportRaw().associateBy { it.storeName to it.key }
                val missing = legacy.filter { (it.storeName to it.key) !in existing }
                database.importRaw(missing)
                val copied = database.exportRaw().associateBy { it.storeName to it.key }
                check(missing.all { copied[it.storeName to it.key] == it }) { "Browser storage migration did not verify" }
                // Leave IndexedDB untouched as a recovery copy; every subsequent write uses SQLite.
                driver.execute(null, "INSERT INTO reaktor_web_migrations (name) VALUES ('indexeddb-v1')", 0).await()
            }
        }
    }).unsafeCast<Promise<Unit>>().await()
}

private suspend fun readLegacyRows(factory: dynamic, name: String): List<RawObject> =
    suspendCancellableCoroutine { continuation ->
        var absent = false
        var database: dynamic = null
        val request = factory.open(name)
        fun fail(reason: dynamic) {
            database?.close()
            if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException("Could not read previous browser storage: ${reason?.message}"),
            )
        }
        request.onupgradeneeded = { _: dynamic ->
            absent = true
            request.transaction.abort()
        }
        request.onerror = { _: dynamic ->
            if (absent) {
                if (continuation.isActive) continuation.resume(emptyList())
            } else fail(request.error)
        }
        request.onsuccess = { _: dynamic ->
            try {
                database = request.result
                if (!continuation.isActive) database.close()
                else if (!(database.objectStoreNames.contains("objects") as Boolean)) {
                    database.close()
                    continuation.resume(emptyList())
                } else {
                    val transaction = database.transaction("objects", "readonly")
                    val rows = transaction.objectStore("objects").getAll()
                    rows.onsuccess = { _: dynamic ->
                        try {
                            val found = rows.result.unsafeCast<Array<dynamic>>().map { row ->
                                RawObject(row.key as String, row.storeName as String, row.payload as String,
                                    (row.createdAt as Double).toLong(), (row.updatedAt as Double).toLong())
                            }
                            database.close()
                            if (continuation.isActive) continuation.resume(found)
                        } catch (failure: Throwable) {
                            database.close()
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                    rows.onerror = { _: dynamic -> fail(rows.error) }
                    transaction.onabort = { _: dynamic -> fail(transaction.error) }
                }
            } catch (failure: Throwable) { fail(failure) }
        }
        continuation.invokeOnCancellation { database?.close() }
    }
