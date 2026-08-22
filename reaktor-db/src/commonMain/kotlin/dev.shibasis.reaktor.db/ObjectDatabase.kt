package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import dev.shibasis.reaktor.db.core.ObjectStoreConfig
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

data class StoredObject<T: Any>(
    val key: String,
    val value: T,
    val storeName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sizeBytes: Long = 0
)

/**
 * A stored object with its payload left encoded, so a whole store can be copied without knowing
 * the types it holds — the shape backup and migration need, since one store usually mixes types
 * that no single [KSerializer] covers.
 */
@Serializable
data class RawObject(
    val key: String,
    val storeName: String,
    val payload: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ObjectAddress(
    val storeName: String,
    val key: String,
)

data class ObjectStateKey(
    val key: String,
    val type: KClass<*>,
)

enum class Origin {
    Local,
    External,
    Sync,
    Migration,
}

sealed class DatabaseEvent {
    data class Put<T : Any>(
        val storeName: String,
        val key: String,
        val stored: StoredObject<T>,
        val origin: Origin = Origin.Local,
    ) : DatabaseEvent()

    data class Invalidated(
        val storeName: String,
        val key: String,
        val origin: Origin = Origin.External,
    ) : DatabaseEvent()

    data class Get(val storeName: String, val key: String) : DatabaseEvent()
    data class Delete(
        val storeName: String,
        val key: String,
        val origin: Origin = Origin.Local,
    ) : DatabaseEvent()

    data class Clear(
        val storeName: String,
        val origin: Origin = Origin.Local,
    ) : DatabaseEvent()

    data class GetAll(val storeName: String): DatabaseEvent()
    data object ClearAll : DatabaseEvent()
}

abstract class ObjectDatabase(
    val objectSerializer: ObjectSerializer<*>
) {
    private val _events = MutableSharedFlow<DatabaseEvent>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    private val stores = ConcurrentHashMap<String, ObjectStore>()

    fun store(
        storeName: String,
        configure: ObjectStoreConfig.() -> Unit = {},
    ): ObjectStore {
        return stores.getOrPut(storeName) {
            ObjectStore(
                database = this,
                storeName = storeName,
                config = ObjectStoreConfig().apply(configure),
            )
        }
    }

    suspend fun <T : Any> put(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
        origin: Origin = Origin.Local,
    ): StoredObject<T> {
        val stored = putRaw(storeName, key, value, serializer)
        publish(DatabaseEvent.Put(storeName, key, stored, origin))
        return stored
    }

    suspend fun <T : Any> get(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>? {
        val stored = getRaw(storeName, key, type, serializer)
        publish(DatabaseEvent.Get(storeName, key))
        return stored
    }

    suspend fun <T : Any> getAll(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>> {
        val values = getAllRaw(storeName, type, serializer)
        publish(DatabaseEvent.GetAll(storeName))
        return values
    }

    suspend fun delete(
        storeName: String,
        key: String,
        origin: Origin = Origin.Local,
    ) {
        deleteRaw(storeName, key)
        publish(DatabaseEvent.Delete(storeName, key, origin))
    }

    suspend fun clear(
        storeName: String,
        origin: Origin = Origin.Local,
    ) {
        clearRaw(storeName)
        publish(DatabaseEvent.Clear(storeName, origin))
    }

    suspend fun clear(origin: Origin = Origin.Local) {
        clearRaw()
        publish(DatabaseEvent.ClearAll)
    }

    suspend fun invalidate(
        storeName: String,
        key: String,
        origin: Origin = Origin.External,
    ) {
        publish(DatabaseEvent.Invalidated(storeName, key, origin))
    }

    /**
     * Every stored object with its payload untouched — what a backup, an export, or a migration
     * needs. Pass [storeName] to limit it to a single store.
     *
     * Only meaningful for text-backed databases: a binary payload has no lossless string form,
     * so those implementations reject it rather than corrupt the data.
     */
    open suspend fun exportRaw(storeName: String? = null): List<RawObject> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support raw export.",
        )

    /**
     * Writes [items] back verbatim, replacing whatever sits at those keys.
     *
     * Open [ObjectState]s are invalidated afterwards so they reload instead of serving what they
     * cached before the import — without this a restore looks like a no-op until the app restarts.
     */
    suspend fun importRaw(
        items: List<RawObject>,
        origin: Origin = Origin.Migration,
    ) {
        if (items.isEmpty()) return
        importRawInternal(items)
        items.forEach { invalidate(it.storeName, it.key, origin) }
    }

    protected open suspend fun importRawInternal(items: List<RawObject>): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support raw import.",
        )

    private suspend fun publish(event: DatabaseEvent) {
        when (event) {
            is DatabaseEvent.Put<*> ->
                stores[event.storeName]?.onDatabaseEvent(event)

            is DatabaseEvent.Invalidated ->
                stores[event.storeName]?.onDatabaseEvent(event)

            is DatabaseEvent.Delete ->
                stores[event.storeName]?.onDatabaseEvent(event)

            is DatabaseEvent.Clear ->
                stores[event.storeName]?.onDatabaseEvent(event)

            DatabaseEvent.ClearAll ->
                stores.values().forEach { it.onDatabaseEvent(event) }

            is DatabaseEvent.Get,
            is DatabaseEvent.GetAll -> {
                // Read events are telemetry only.
            }
        }

        _events.emit(event)
    }

    protected abstract suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T>

    protected abstract suspend fun <T : Any> getRaw(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>?

    protected abstract suspend fun <T : Any> getAllRaw(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>>

    protected abstract suspend fun deleteRaw(storeName: String, key: String)

    protected abstract suspend fun clearRaw(storeName: String)

    protected abstract suspend fun clearRaw()
}

var Feature.Database by CreateSlot<ObjectDatabase>()
