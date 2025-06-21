package dev.shibasis.reaktor.db.store

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

data class StoredObject<T: Any>(
    val key: String,
    val value: T,
    val storeName: String,
    val createdAt: Long,
    val updatedAt: Long
)

interface TimestampProvider {
    fun getTimestamp(): Long
}

class DefaultTimestampProvider : TimestampProvider {
    override fun getTimestamp(): Long = Clock.System.now().toEpochMilliseconds()
}

/*
1. Gemini -> https://gemini.google.com/app/11ff9fb0c1b8dbf0
2. GPT -> https://chatgpt.com/c/679d4d1a-b424-8012-b0a3-a2eb75eaf967

Kotlin like Java has Type Erasure, so you need to ensure you use the same type for crud operations.
(we can't store it, probably through reified types, but ignore for now)
*/
abstract class ObjectDatabase(
    val objectSerializer: ObjectSerializer<*>,
    protected val cachePolicy: CachePolicy,
    protected val timestampProvider: TimestampProvider
) {
    abstract suspend fun <T : Any> put(storeName: String, key: String, value: T, serializer: KSerializer<T>)
    abstract suspend fun <T : Any> get(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>?
    abstract suspend fun <T : Any> getAll(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>>
    abstract suspend fun delete(storeName: String, key: String)
    abstract suspend fun clear(storeName: String)
    abstract suspend fun clear()
}

var Feature.Database by CreateSlot<ObjectDatabase>()

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


    suspend fun delete(key: String) = objectDatabase.delete(key, storeName)
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

/*
todo:
Enhancement:
    Add a high performance reliable SyncAdapter

Experiment:
    Instead of the usual (api, repository, interactions), does a DataHolder abstraction make sense ?

*/
abstract class Repository(
    storeName: String,
    database: ObjectDatabase = Feature.Database ?: throw IllegalStateException("You need to initialize the database"),
): Adapter<ObjectDatabase>(database) {
    protected val store = ObjectStore(database, storeName)
    protected suspend inline fun<reified T: Any> write(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = store.writeThrough(cacheKey, fetcher)

    protected suspend inline fun<reified T: Any> writeAndGet(
        cacheKey: String,
        crossinline fetcher: suspend () -> Result<T>
    ) = write(cacheKey, fetcher).result
}



























