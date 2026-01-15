package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import dev.shibasis.reaktor.db.DatabaseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.reflect.KClass

class ObjectStore(
    val objectDatabase: ObjectDatabase,
    val storeName: String,
    // Scope used for background persistence in delegates
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    inline fun <reified T> serializer(): KSerializer<T> {
        return objectDatabase.objectSerializer.serializersModule.serializer()
    }

    /* NEW: Delegate Property Provider
       Usage: var theme by store.item("Dark")
    */
    inline fun <reified T : Any> item(
        defaultValue: T,
        key: String? = null
    ): ReactiveDelegate<T> {
        return ReactiveDelegate(
            store = this,
            key = key,
            defaultValue = defaultValue,
            serializer = serializer(),
            scope = scope
        )
    }

    /* UPDATED: Put now emits events
    */
    suspend inline fun <reified T: Any> put(key: String, value: T) {
        put(key, value, serializer())
    }

    // Helper for non-reified calls (used by Delegate)
    suspend fun <T : Any> put(key: String, value: T, serializer: KSerializer<T>) {
        objectDatabase.put(storeName, key, value, serializer)
        objectDatabase.emit(DatabaseEvent.Write(storeName, key))
    }

    suspend inline fun <reified T: Any> get(key: String): StoredObject<T>? =
        objectDatabase.get(storeName, key, T::class, serializer())

    // Helper for non-reified calls (used by Delegate)
    suspend fun <T : Any> get(key: String, serializer: KSerializer<T>): StoredObject<T>? =
        objectDatabase.get(storeName, key, serializer.descriptor.kind::class as KClass<T>, serializer) // Cast needed due to erasure

    suspend inline fun <reified T : Any> getAll(): List<StoredObject<T>> =
        objectDatabase.getAll(storeName, T::class, serializer())

    /* UPDATED: Delete now emits events
    */
    suspend fun delete(key: String) {
        objectDatabase.delete(storeName, key)
        objectDatabase.emit(DatabaseEvent.Delete(storeName, key))
    }

    suspend fun clear() {
        objectDatabase.clear(storeName)
        objectDatabase.emit(DatabaseEvent.Clear(storeName))
    }

    // --- Existing Caching Logic (Preserved) ---
    data class CacheResult<T>(
        val result: Result<T>,
        val isCached: Boolean
    )

    var enableWriteThrough = true

    suspend inline fun<reified T: Any> writeThrough(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>,
    ): CacheResult<T> {
        try {
            if (!enableWriteThrough) return CacheResult(fetcher(), false)

            val cachedData = get<T>(cacheKey)
            if (cachedData != null) return CacheResult(succeed(cachedData.value), true)

            val result = fetcher()
            val data = result.getOrNull() ?: return CacheResult(fail(result.exceptionOrNull()!!), false)

            put(cacheKey, data)
            return CacheResult(succeed(data), false)
        } catch (e: Throwable) {
            return CacheResult(fail(e), false)
        }
    }
}