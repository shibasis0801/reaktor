package dev.shibasis.reaktor.db.store


interface ObjectStoreManager {
    fun getObjectStore(storeName: String): ObjectStore
    fun getAllObjectStores(): List<ObjectStore>
    suspend fun deleteObjectStore(storeName: String)
}

