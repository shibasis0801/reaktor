package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.db.ObjectAddress
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

class ObjectState<T : Any> internal constructor(
    private val store: ObjectStore,
    val key: String,
    val type: KClass<T>,
    val serializer: KSerializer<T>,
) {
    private val log = "ObjectState".logger()

    private val _stored = MutableStateFlow<StoredObject<T>?>(null)

    val stored: StateFlow<StoredObject<T>?> =
        _stored.asStateFlow()

    val storedValue: StoredObject<T>?
        get() = _stored.value

    val value: T?
        get() = _stored.value?.value

    val values: Flow<T?> =
        stored.map { it?.value }.distinctUntilChanged()

    suspend fun load(
        force: Boolean = false,
    ): StoredObject<T>? {
        if (!force) {
            _stored.value?.let { return it }

            store.cache.get(ObjectAddress(store.storeName, key), type)
                ?.takeIf { store.retention.isValid(it) }
                ?.let { cached ->
                    _stored.value = cached
                    return cached
                }
        }

        return store.serial(key) {
            if (!force) {
                _stored.value?.let { return@serial it }

                store.cache.get(ObjectAddress(store.storeName, key), type)
                    ?.takeIf { store.retention.isValid(it) }
                    ?.let { cached ->
                        _stored.value = cached
                        return@serial cached
                    }
            }

            readLatestLocked(force = force)
        }
    }

    suspend fun refresh(): StoredObject<T>? =
        load(force = true)

    suspend fun get(): T? =
        load()?.value

    suspend fun require(): T =
        get() ?: error("Object not found: ${store.storeName}/$key")

    suspend fun set(value: T): StoredObject<T> =
        store.serial(key) {
            store.database.put(
                storeName = store.storeName,
                key = key,
                value = value,
                serializer = serializer,
            )
        }

    suspend fun update(
        transform: (T) -> T,
    ): StoredObject<T> =
        store.serial(key) {
            val current = readLatestLocked()
                ?: error("Cannot update missing object: ${store.storeName}/$key")

            val next = transform(current.value)

            store.database.put(
                storeName = store.storeName,
                key = key,
                value = next,
                serializer = serializer,
            )
        }

    suspend fun updateOrCreate(
        create: () -> T,
        transform: (T) -> T,
    ): StoredObject<T> =
        store.serial(key) {
            val current = readLatestLocked()?.value ?: create()
            val next = transform(current)

            store.database.put(
                storeName = store.storeName,
                key = key,
                value = next,
                serializer = serializer,
            )
        }

    suspend fun delete() {
        store.database.delete(store.storeName, key)
    }

    private suspend fun readLatestLocked(force: Boolean = false): StoredObject<T>? {
        if (!force) {
            _stored.value?.let { return it }

            store.cache.get(ObjectAddress(store.storeName, key), type)
                ?.takeIf { store.retention.isValid(it) }
                ?.let { cached ->
                    _stored.value = cached
                    return cached
                }
        }

        val loaded = try {
            store.database.get(
                storeName = store.storeName,
                key = key,
                type = type,
                serializer = serializer,
            )
        } catch (exception: UnreadableObjectException) {
            // Callers overwhelmingly answer a null with a default and write it back, so returning
            // null over a payload still sitting in the store would quietly destroy it on the next
            // save. Set the bytes aside first; then absent is the truth.
            val quarantined = store.database.quarantine(store.storeName, key)
            log.warn {
                if (quarantined != null) {
                    "Set aside unreadable ${store.storeName}/$key as $quarantined: ${exception.cause?.message}"
                } else {
                    "Cannot read ${store.storeName}/$key and cannot set it aside: ${exception.cause?.message}"
                }
            }
            null
        }

        if (loaded == null) {
            store.cache.remove(ObjectAddress(store.storeName, key))
            _stored.value = null
            return null
        }

        if (!store.retention.isValid(loaded)) {
            store.cache.remove(ObjectAddress(store.storeName, key))
            store.database.delete(store.storeName, key)
            _stored.value = null
            return null
        }

        store.cache.put(loaded)
        _stored.value = loaded
        return loaded
    }

    @Suppress("UNCHECKED_CAST")
    internal suspend fun applyDatabaseEvent(event: DatabaseEvent) {
        when (event) {
            is DatabaseEvent.Put<*> -> {
                if (event.storeName != store.storeName || event.key != key) return

                val stored = event.stored

                if (type.isInstance(stored.value)) {
                    _stored.value = stored as StoredObject<T>
                } else {
                    refresh()
                }
            }

            is DatabaseEvent.Invalidated -> {
                if (event.storeName == store.storeName && event.key == key) {
                    refresh()
                }
            }

            is DatabaseEvent.Delete -> {
                if (event.storeName == store.storeName && event.key == key) {
                    _stored.value = null
                }
            }

            is DatabaseEvent.Clear -> {
                if (event.storeName == store.storeName) {
                    _stored.value = null
                }
            }

            DatabaseEvent.ClearAll -> {
                _stored.value = null
            }

            is DatabaseEvent.Get,
            is DatabaseEvent.GetAll -> {
                // Read events do not mutate live object state.
            }
        }
    }
}
