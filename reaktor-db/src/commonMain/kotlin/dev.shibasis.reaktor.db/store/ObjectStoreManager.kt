package dev.shibasis.reaktor.db.store

import JsonSqliteObjectDatabase
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ObjectStoreManager {
    fun getObjectStore(storeName: String): ObjectDatabase
    fun getAllObjectStores(): List<ObjectDatabase>
    suspend fun deleteObjectStore(storeName: String)
}

class ObjectStoreManagerImpl(
    private val driver: SqlDriver,
    private val cacheEvictionPolicy: CacheEvictionPolicy,
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider()
) : ObjectStoreManager {

    private val storeMutex = Mutex()
    private val stores = mutableMapOf<String, ObjectDatabase>()

    override fun getObjectStore(storeName: String): ObjectDatabase {
        return stores.getOrPut(storeName) {
            JsonSqliteObjectDatabase(driver, cacheEvictionPolicy, timestampProvider)
        }
    }

    override fun getAllObjectStores(): List<ObjectDatabase> {
        return stores.values.toList()
    }

    override suspend fun deleteObjectStore(storeName: String) {
        storeMutex.withLock {
            val store = stores[storeName]
            store?.clearStore(storeName)
            stores.remove(storeName)
        }
    }
}