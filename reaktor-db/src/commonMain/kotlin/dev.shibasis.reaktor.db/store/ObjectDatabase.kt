package dev.shibasis.reaktor.db.store

import dev.shibasis.reaktor.db.store.concrete.LRUCachePolicy
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

data class StoredObject<T: Any>(
    val key: String,
    val value: T,
    val type: String = value::class.simpleName ?: throw IllegalArgumentException("Anonymous classes not supported"),
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

sealed interface ObjectSerializer<Output> {
    val serializersModule: SerializersModule
    fun<Input> serialize(serializer: KSerializer<Input>, value: Input): Output
    fun<Input> deserialize(serializer: KSerializer<Input>, data: Output): Input

    fun<Result> choose(json: Result, flex: Result): Result {
        return when(this) {
            is JsonSerializer -> json
            is FlexSerializer -> flex
        }
    }

}

class JsonSerializer(private val json: Json = Json): ObjectSerializer<String> {
    override val serializersModule: SerializersModule
        get() = json.serializersModule

    override fun <T> serialize(serializer: KSerializer<T>, value: T): String {
        return json.encodeToString(serializer, value)
    }

    override fun <T> deserialize(serializer: KSerializer<T>, data: String): T {
        return json.decodeFromString(serializer, data)
    }
}

class FlexSerializer: ObjectSerializer<ByteArray> {
    override val serializersModule: SerializersModule
        get() = TODO("Not yet implemented")


    override fun <Input> serialize(serializer: KSerializer<Input>, value: Input): ByteArray {
        TODO("Not yet implemented")
    }

    override fun <Input> deserialize(serializer: KSerializer<Input>, data: ByteArray): Input {
        TODO("Not yet implemented")
    }
}


abstract class ObjectDatabase(
    val objectSerializer: ObjectSerializer<*> = JsonSerializer(),
    protected val cachePolicy: CachePolicy = LRUCachePolicy(100),
    protected val timestampProvider: TimestampProvider = DefaultTimestampProvider()
) {
    abstract suspend fun <T : Any> put(key: String, value: T, serializer: KSerializer<T>, storeName: String)
    abstract suspend fun <T : Any> get(key: String, storeName: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>?
    abstract suspend fun <T : Any> getAll(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>>
    abstract suspend fun delete(key: String, storeName: String)
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
        objectDatabase.put(key, value, serializer(), storeName)

    suspend inline fun <reified T: Any> get(key: String): StoredObject<T>? =
        objectDatabase.get(key, storeName, T::class, serializer())


    suspend inline fun <reified T : Any> getAll(storeName: String): List<StoredObject<T>> =
        objectDatabase.getAll(storeName, T::class, serializer())


    suspend fun delete(key: String) = objectDatabase.delete(key, storeName)
    suspend fun clearStore() = objectDatabase.clear(storeName)
}
