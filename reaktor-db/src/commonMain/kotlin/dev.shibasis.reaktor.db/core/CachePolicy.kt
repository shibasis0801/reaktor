package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.StoredObject
import kotlinx.datetime.Clock

// todo needs upgrades
interface CachePolicy {
    // return null in order to ignore the access, the object if you want to use it.
    suspend fun<T: Any> onItemAccess(storedObject: StoredObject<T>): StoredObject<T>?
    suspend fun onItemInsertion(key: String, storeName: String)
    suspend fun onItemUpdate(key: String, storeName: String)
    suspend fun onItemDeletion(key: String, storeName: String)
    // Returns List of (Key, StoreName)
    suspend fun findKeysToEvict(storeName: String, currentTimeMillis: Long = Clock.System.now().toEpochMilliseconds()): List<Pair<String, String>> // Returns List of (Key, StoreName)
}