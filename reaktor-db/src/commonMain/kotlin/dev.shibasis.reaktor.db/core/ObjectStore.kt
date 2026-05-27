package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.db.ObjectAddress
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.ObjectStateKey
import dev.shibasis.reaktor.db.Origin
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class ObjectStoreConfig {
    var cache: ObjectCache = LruObjectCache()
    var retention: RetentionPolicy = KeepForever
    var lockStripes: Int = 64
}

class KeyedGate<K>(
    stripes: Int = 64,
) {
    private val locks = Array(stripes.coerceAtLeast(1)) {
        Mutex()
    }

    suspend fun <R> withKey(
        key: K,
        block: suspend () -> R,
    ): R {
        val index = (key.hashCode() and Int.MAX_VALUE) % locks.size
        return locks[index].withLock {
            block()
        }
    }
}

class ObjectStore internal constructor(
    val database: ObjectDatabase,
    val storeName: String,
    config: ObjectStoreConfig,
) {
    val cache: ObjectCache = config.cache
    val retention: RetentionPolicy = config.retention

    private val states = ConcurrentHashMap<ObjectStateKey, ObjectState<*>>()
    private val stateTypesByKey = ConcurrentHashMap<String, KClass<*>>()
    private val gate = KeyedGate<String>(config.lockStripes)

    inline fun <reified T : Any> serializer(): KSerializer<T> {
        return database.objectSerializer.serializersModule.serializer()
    }

    inline fun <reified T : Any> state(key: String): ObjectState<T> =
        state(key, T::class, serializer())

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> state(
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): ObjectState<T> {
        val previousType = stateTypesByKey.putIfAbsent(key, type)

        require(previousType == null || previousType == type) {
            "Object '$storeName/$key' is already opened as ${previousType?.simpleName}, " +
                "cannot open as ${type.simpleName}."
        }

        val stateKey = ObjectStateKey(key, type)

        return states.getOrPut(stateKey) {
            ObjectState(
                store = this,
                key = key,
                type = type,
                serializer = serializer,
            )
        } as ObjectState<T>
    }

    suspend inline fun <reified T : Any> read(key: String): StoredObject<T>? =
        state<T>(key).load()

    suspend inline fun <reified T : Any> write(
        key: String,
        value: T,
    ): StoredObject<T> =
        state<T>(key).set(value)

    suspend inline fun <reified T : Any> update(
        key: String,
        noinline transform: (T) -> T,
    ): StoredObject<T> =
        state<T>(key).update(transform)

    suspend inline fun <reified T : Any> put(
        key: String,
        value: T,
    ): StoredObject<T> =
        write(key, value)

    suspend inline fun <reified T : Any> get(key: String): StoredObject<T>? =
        read(key)

    suspend inline fun <reified T : Any> getAll(): List<StoredObject<T>> {
        val values = database.getAll(
            storeName = storeName,
            type = T::class,
            serializer = serializer(),
        )

        return values.filter { stored ->
            if (retention.isValid(stored)) {
                applyLoaded(stored)
                true
            } else {
                database.delete(storeName, stored.key)
                false
            }
        }
    }

    suspend fun delete(key: String) {
        database.delete(storeName, key)
    }

    suspend fun clear() {
        database.clear(storeName)
    }

    internal suspend fun <R> serial(
        key: String,
        block: suspend () -> R,
    ): R = gate.withKey(key, block)

    @PublishedApi
    internal suspend fun applyLoaded(stored: StoredObject<*>) {
        cache.put(stored)
        states.entries()
            .filter { it.first.key == stored.key }
            .forEach { it.second.applyDatabaseEvent(DatabaseEvent.Put(storeName, stored.key, stored, Origin.Local)) }
    }

    internal suspend fun onDatabaseEvent(event: DatabaseEvent) {
        when (event) {
            is DatabaseEvent.Put<*> -> {
                cache.put(event.stored)

                states.entries()
                    .filter { it.first.key == event.key }
                    .forEach { it.second.applyDatabaseEvent(event) }
            }

            is DatabaseEvent.Invalidated -> {
                cache.remove(ObjectAddress(storeName, event.key))

                states.entries()
                    .filter { it.first.key == event.key }
                    .forEach { it.second.applyDatabaseEvent(event) }
            }

            is DatabaseEvent.Delete -> {
                cache.remove(ObjectAddress(storeName, event.key))

                states.entries()
                    .filter { it.first.key == event.key }
                    .forEach { it.second.applyDatabaseEvent(event) }
            }

            is DatabaseEvent.Clear -> {
                cache.clear(storeName)

                states.values().forEach {
                    it.applyDatabaseEvent(event)
                }
            }

            DatabaseEvent.ClearAll -> {
                cache.clear()

                states.values().forEach {
                    it.applyDatabaseEvent(event)
                }
            }

            is DatabaseEvent.Get,
            is DatabaseEvent.GetAll -> {
                // Read events are telemetry only.
            }
        }
    }
}
