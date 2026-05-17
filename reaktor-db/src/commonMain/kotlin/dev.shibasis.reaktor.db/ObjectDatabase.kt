package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class StoredObject<T: Any>(
    val key: String,
    val value: T,
    val storeName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sizeBytes: Long = 0
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
    val objectSerializer: ObjectSerializer<*>
) {
    private val _events = MutableSharedFlow<DatabaseEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    private val stores = hashMapOf<String, ObjectStore>()

    fun store(storeName: String): ObjectStore {
        return stores.getOrPut(storeName) { ObjectStore(this, storeName) }
    }

    abstract suspend fun <T : Any> put(storeName: String, key: String, value: T, serializer: KSerializer<T>): StoredObject<T>
    abstract suspend fun <T : Any> get(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>?
    abstract suspend fun <T : Any> getAll(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>>
    abstract suspend fun delete(storeName: String, key: String)
    abstract suspend fun clear(storeName: String)
    abstract suspend fun clear()

    suspend fun emit(event: DatabaseEvent) {
        _events.emit(event)
    }
}

var Feature.Database by CreateSlot<ObjectDatabase>()
