package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class ObjectStore(
    val database: ObjectDatabase,
    val storeName: String,
    var eviction: EvictionPolicy = NoEviction,
    val memory: MemoryCache = MemoryCache()
) {
    inline fun <reified T> serializer(): KSerializer<T> {
        return database.objectSerializer.serializersModule.serializer()
    }

    @PublishedApi
    internal val flows = hashMapOf<String, ObjectFlow<*>>()

    suspend inline fun <reified T: Any> put(key: String, value: T): StoredObject<T> {
        val stored = database.put(storeName, key, value, serializer())
        database.emit(DatabaseEvent.Put(storeName, key))
        memory.put(stored)

        val evictions = eviction.onWrite(key, stored.sizeBytes)
        for (evictKey in evictions) {
            database.delete(storeName, evictKey)
            memory.remove(evictKey)
            flows.remove(evictKey)
        }

        // Update existing flow if present; don't create one just for the write
        existingFlow<T>(key)?.update(stored)
        return stored
    }

    suspend inline fun <reified T: Any> get(key: String): StoredObject<T>? {
        memory.get<T>(key)?.let { return it }

        val stored = database.get(storeName, key, T::class, serializer()) ?: return null

        if (!eviction.isValid(stored)) {
            database.delete(storeName, key)
            eviction.onDelete(key)
            return null
        }

        database.emit(DatabaseEvent.Get(storeName, key))
        memory.put(stored)
        return stored
    }

    suspend inline fun <reified T: Any> getAll(): List<StoredObject<T>> {
        val results = database.getAll(storeName, T::class, serializer())
        database.emit(DatabaseEvent.GetAll(storeName))

        return results.filter { stored ->
            if (eviction.isValid(stored)) {
                memory.put(stored)
                true
            } else {
                database.delete(storeName, stored.key)
                eviction.onDelete(stored.key)
                false
            }
        }
    }

    suspend fun delete(key: String) {
        database.delete(storeName, key)
        database.emit(DatabaseEvent.Delete(storeName, key))
        memory.remove(key)
        eviction.onDelete(key)
        flows.remove(key)
    }

    suspend fun clear() {
        database.clear(storeName)
        database.emit(DatabaseEvent.Clear(storeName))
        memory.clear()
        eviction.clear()
        flows.clear()
    }

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T: Any> existingFlow(key: String): ObjectFlow<T>? {
        return flows[key] as? ObjectFlow<T>
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T: Any> flow(key: String): ObjectFlow<T> {
        return flows.getOrPut(key) {
            ObjectFlow<T>(this, key) { get<T>(key) }
        } as ObjectFlow<T>
    }
}
