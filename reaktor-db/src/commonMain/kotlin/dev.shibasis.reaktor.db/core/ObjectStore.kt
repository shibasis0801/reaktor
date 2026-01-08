package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class ObjectStore(
    val objectDatabase: ObjectDatabase,
    val storeName: String
) {
    inline fun <reified T> serializer(): KSerializer<T> {
        return objectDatabase.objectSerializer.serializersModule.serializer()
    }

    suspend inline fun <reified T: Any> put(key: String, value: T) =
        objectDatabase.put(storeName, key, value, serializer())

    suspend inline fun <reified T: Any> get(key: String): StoredObject<T>? =
        objectDatabase.get(storeName, key, T::class, serializer())


    suspend inline fun <reified T : Any> getAll(): List<StoredObject<T>> =
        objectDatabase.getAll(storeName, T::class, serializer())


    suspend fun delete(key: String) = objectDatabase.delete(storeName, key)
    suspend fun clear() = objectDatabase.clear(storeName)


    data class CacheResult<T>(
        val result: Result<T>,
        val isCached: Boolean
    )

    var enableWriteThrough = true
    /** val (data, isCached) = writeThrough(cacheKey, fetcher, validator)  */
    suspend inline fun<reified T: Any> writeThrough(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>,
    ): CacheResult<T> {
        try {
            if (!enableWriteThrough) return CacheResult(fetcher(), false)

            val cachedData = get<T>(cacheKey)
            if (cachedData != null) return CacheResult(succeed(cachedData.value), true)

            val result = fetcher()
            val data =
                result.getOrNull() ?: return CacheResult(fail(result.exceptionOrNull()!!), false)
            put(cacheKey, data)
            return CacheResult(succeed(data), false)
        } catch (e: Throwable) {
            return CacheResult(fail(e), false)
        }
    }
}

