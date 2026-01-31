package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class ObjectStore internal constructor(
    val objectDatabase: ObjectDatabase,
    val storeName: String
) {
    companion object {
        operator fun invoke(objectDatabase: ObjectDatabase, storeName: String) =
            objectDatabase.singletonStore(storeName)
    }

    inline fun <reified T> serializer(): KSerializer<T> {
        return objectDatabase.objectSerializer.serializersModule.serializer()
    }

    val objects = hashMapOf<String, Pair<KClass<*>, ObjectFlow<*>>>()

    suspend inline fun <reified T: Any> write(key: String, value: T) =
        objectDatabase.put(storeName, key, value, serializer())

    suspend inline fun <reified T: Any> read(key: String): StoredObject<T>? =
        objectDatabase.get(storeName, key, T::class, serializer())

    @Suppress("UNCHECKED_CAST")
    inline operator fun <reified T: Any> get(key: String): ObjectFlow<T> {
        return objects.getOrPut(key) {
            val objectFlow = ObjectFlow(this, key) {
                read<T>(key)
            }

            Pair(T::class, objectFlow)
        }.second as ObjectFlow<T>
    }

    suspend inline operator fun <reified T: Any> set(key: String, value: T): ObjectFlow<T> {
        val storedObject = write<T>(key, value)
        return get<T>(key).apply { _internal_emit(storedObject) }
    }

    suspend inline fun <reified T : Any> getAll(): List<StoredObject<T>> =
        objectDatabase.getAll(storeName, T::class, serializer())

    suspend fun delete(key: String) = objectDatabase.delete(storeName, key)
    suspend fun clear() = objectDatabase.clear(storeName)
}