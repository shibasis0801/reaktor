package dev.shibasis.reaktor.db

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.WeakRef
import dev.shibasis.reaktor.core.utils.fail
import dev.shibasis.reaktor.core.utils.succeed
import dev.shibasis.reaktor.db.core.CachePolicy
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.db.core.TimestampProvider
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

sealed class DatabaseEvent {
    data class Put(val storeName: String, val key: String) : DatabaseEvent()
    data class Get(val storeName: String, val key: String) : DatabaseEvent()
    data class Delete(val storeName: String, val key: String) : DatabaseEvent()
    data class Clear(val storeName: String) : DatabaseEvent()
    data class GetAll(val storeName: String): DatabaseEvent()
    data object ClearAll : DatabaseEvent()
}

abstract class ObjectDatabase(
    val objectSerializer: ObjectSerializer<*>,
    protected val cachePolicy: CachePolicy, // todo -> critical -> move cache policy to store level not db level.
    protected val timestampProvider: TimestampProvider
) {
    private val _events = MutableSharedFlow<DatabaseEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    suspend fun emit(event: DatabaseEvent) {
        _events.emit(event)
    }

    val stores = hashMapOf<String, WeakRef<ObjectStore>>()
    internal fun singletonStore(storeName: String): ObjectStore {
        var store = stores[storeName]?.get()
        if (store != null) {
            Logger.e { "Store $storeName already configured, not reconfiguring" }
            return store
        }
        store = ObjectStore(this, storeName)
        stores[storeName] = WeakRef(store)
        return store
    }

    abstract suspend fun <T : Any> put(storeName: String, key: String, value: T, serializer: KSerializer<T>): StoredObject<T>
    abstract suspend fun <T : Any> get(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>?
    abstract suspend fun <T : Any> getAll(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>>
    abstract suspend fun delete(storeName: String, key: String)
    abstract suspend fun clear(storeName: String)
    abstract suspend fun clear()
}

var Feature.Database by CreateSlot<ObjectDatabase>()


























