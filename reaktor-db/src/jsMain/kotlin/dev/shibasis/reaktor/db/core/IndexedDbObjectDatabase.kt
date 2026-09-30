package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass

class IndexedDbUnavailableException(message: String) : IllegalStateException(message)

class IndexedDbObjectDatabase(
    name: String,
    private val text: TextSerializer = TextSerializer(),
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider(),
    factory: dynamic = js("globalThis.indexedDB"),
) : ObjectDatabase(text) {
    val databaseName = "reaktor-objects-$name"
    private val connection = CompletableDeferred<dynamic>()
    private val log = "IndexedDbObjectDatabase".logger()

    init {
        open(factory)
    }

    suspend fun ready() {
        connection.await()
    }

    suspend fun close() {
        connection.await().close()
    }

    override suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T> {
        val payload = text.serialize(serializer, value)
        val now = timestampProvider.getTimestamp().toDouble()
        val written = transaction<dynamic>(ReadWrite) { store, finish ->
            val existing = store.get(address(storeName, key))
            existing.onsuccess = { _: dynamic ->
                val previous = existing.result
                val createdAt = if (previous == null) now else previous.createdAt as Double
                val row = record(storeName, key, payload, createdAt, now)
                store.put(row)
                finish(row)
            }
        }
        return decode(written, serializer)
    }

    override suspend fun <T : Any> getRaw(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>? {
        val row = transaction<dynamic>(ReadOnly) { store, finish ->
            val request = store.get(address(storeName, key))
            request.onsuccess = { _: dynamic -> finish(request.result) }
        }
        return if (row == null) null else decode(row, serializer)
    }

    override suspend fun <T : Any> getAllRaw(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>> = rowsOf(storeName).mapNotNull { row ->
        try {
            decode(row, serializer)
        } catch (exception: UnreadableObjectException) {
            log.warn { "Skipping unreadable row in $storeName: ${exception.message}" }
            null
        }
    }

    override suspend fun deleteRaw(storeName: String, key: String) {
        transaction<Unit>(ReadWrite) { store, finish ->
            store.delete(address(storeName, key))
            finish(Unit)
        }
    }

    override suspend fun clearRaw(storeName: String) {
        transaction<Unit>(ReadWrite) { store, finish ->
            val cursor = store.index(ByStore).openKeyCursor(storeName)
            cursor.onsuccess = { _: dynamic ->
                val position = cursor.result
                if (position == null) {
                    finish(Unit)
                } else {
                    store.delete(position.primaryKey)
                    position.`continue`()
                }
            }
        }
    }

    override suspend fun clearRaw() {
        transaction<Unit>(ReadWrite) { store, finish ->
            store.clear()
            finish(Unit)
        }
    }

    override suspend fun renameRaw(storeName: String, key: String, newKey: String): Boolean =
        transaction<Boolean>(ReadWrite) { store, finish ->
            val taken = store.get(address(storeName, newKey))
            taken.onsuccess = { _: dynamic ->
                if (taken.result != null) {
                    finish(false)
                } else {
                    val current = store.get(address(storeName, key))
                    current.onsuccess = { _: dynamic ->
                        val row = current.result
                        if (row == null) {
                            finish(false)
                        } else {
                            store.put(record(storeName, newKey, row.payload as String, row.createdAt as Double, row.updatedAt as Double))
                            store.delete(address(storeName, key))
                            finish(true)
                        }
                    }
                }
            }
        }

    override suspend fun exportRaw(storeName: String?): List<RawObject> =
        rowsOf(storeName).map { row ->
            RawObject(
                key = row.key as String,
                storeName = row.storeName as String,
                payload = row.payload as String,
                createdAt = (row.createdAt as Double).toLong(),
                updatedAt = (row.updatedAt as Double).toLong(),
            )
        }

    override suspend fun importRawInternal(items: List<RawObject>) {
        transaction<Unit>(ReadWrite) { store, finish ->
            var pending = items.size
            items.forEach { item ->
                val existing = store.get(address(item.storeName, item.key))
                existing.onsuccess = { _: dynamic ->
                    val createdAt = existing.result?.createdAt as? Double ?: item.createdAt.toDouble()
                    store.put(record(item.storeName, item.key, item.payload, createdAt, item.updatedAt.toDouble()))
                    pending -= 1
                    if (pending == 0) finish(Unit)
                }
            }
        }
    }

    private suspend fun rowsOf(storeName: String?): List<dynamic> {
        val rows = transaction<Array<dynamic>>(ReadOnly) { store, finish ->
            val request = if (storeName == null) store.getAll() else store.index(ByStore).getAll(storeName)
            request.onsuccess = { _: dynamic -> finish(request.result.unsafeCast<Array<dynamic>>()) }
        }
        return rows.sortedBy { it.createdAt as Double }
    }

    private suspend fun <R> transaction(
        mode: String,
        body: (store: dynamic, finish: (R) -> Unit) -> Unit,
    ): R {
        val database = connection.await()
        return suspendCancellableCoroutine { continuation ->
            val holder = Outcome<R>()
            val handle = try {
                database.transaction(Objects, mode)
            } catch (error: Throwable) {
                continuation.resumeWithException(IndexedDbUnavailableException("Could not start a transaction on $databaseName: ${error.message}"))
                return@suspendCancellableCoroutine
            }
            handle.oncomplete = { _: dynamic ->
                if (continuation.isActive) {
                    if (holder.finished) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(holder.value as R)
                    } else {
                        continuation.resumeWithException(IllegalStateException("$databaseName finished a transaction without an answer"))
                    }
                }
            }
            handle.onabort = { _: dynamic ->
                if (continuation.isActive) {
                    val reason = handle.error?.message as? String ?: "aborted"
                    continuation.resumeWithException(IllegalStateException("$databaseName transaction failed: $reason"))
                }
            }
            continuation.invokeOnCancellation { runCatching { handle.abort() } }
            try {
                body(handle.objectStore(Objects)) { value ->
                    holder.value = value
                    holder.finished = true
                }
            } catch (error: Throwable) {
                runCatching { handle.abort() }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun open(factory: dynamic) {
        if (factory == null) {
            connection.completeExceptionally(IndexedDbUnavailableException("IndexedDB is not available in this runtime"))
            return
        }
        val request = try {
            factory.open(databaseName, SchemaVersion)
        } catch (error: Throwable) {
            connection.completeExceptionally(IndexedDbUnavailableException("IndexedDB refused to open $databaseName: ${error.message}"))
            return
        }
        request.onupgradeneeded = { _: dynamic ->
            val database = request.result
            if (!(database.objectStoreNames.contains(Objects) as Boolean)) {
                val store = database.createObjectStore(Objects, js("({ keyPath: ['storeName', 'key'] })"))
                store.createIndex(ByStore, "storeName", js("({ unique: false })"))
            }
        }
        request.onsuccess = { _: dynamic ->
            val database = request.result
            database.onversionchange = { _: dynamic -> database.close() }
            connection.complete(database)
        }
        request.onerror = { _: dynamic ->
            val reason = request.error?.message as? String ?: "unknown error"
            connection.completeExceptionally(IndexedDbUnavailableException("IndexedDB could not open $databaseName: $reason"))
        }
        request.onblocked = { _: dynamic ->
            log.warn { "$databaseName is waiting for another tab to close an older version" }
        }
    }

    private fun <T : Any> decode(row: dynamic, serializer: KSerializer<T>): StoredObject<T> {
        val storeName = row.storeName as String
        val key = row.key as String
        val payload = row.payload as String
        val value = try {
            text.deserialize(serializer, payload)
        } catch (exception: SerializationException) {
            throw UnreadableObjectException(storeName, key, exception)
        }
        return StoredObject(
            key = key,
            value = value,
            storeName = storeName,
            createdAt = (row.createdAt as Double).toLong(),
            updatedAt = (row.updatedAt as Double).toLong(),
            sizeBytes = payload.length.toLong() * 2,
        )
    }

    private class Outcome<R> {
        var finished = false
        var value: R? = null
    }

    private companion object {
        const val SchemaVersion = 1
        const val Objects = "objects"
        const val ByStore = "byStore"
        const val ReadOnly = "readonly"
        const val ReadWrite = "readwrite"

        fun address(storeName: String, key: String): Array<String> = arrayOf(storeName, key)

        fun record(storeName: String, key: String, payload: String, createdAt: Double, updatedAt: Double): dynamic {
            val row = js("({})")
            row.storeName = storeName
            row.key = key
            row.payload = payload
            row.createdAt = createdAt
            row.updatedAt = updatedAt
            return row
        }
    }
}
