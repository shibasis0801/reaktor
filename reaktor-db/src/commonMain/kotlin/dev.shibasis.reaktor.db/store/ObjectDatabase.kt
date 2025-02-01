package dev.shibasis.reaktor.db.store

import dev.shibasis.reaktor.db.core.ObjectSerializer
import dev.shibasis.reaktor.db.core.TextSerializer
import dev.shibasis.reaktor.db.store.concrete.LRUCachePolicy
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

// Usually you want a server timestamp to ensure cache correctness.
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
}
