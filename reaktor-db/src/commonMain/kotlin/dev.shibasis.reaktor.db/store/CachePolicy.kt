package dev.shibasis.reaktor.db.store

import kotlinx.datetime.Clock


interface CachePolicy {
    suspend fun onItemAccess(key: String, storeName: String)
    suspend fun onItemInsertion(key: String, storeName: String)
    suspend fun onItemUpdate(key: String, storeName: String)
    suspend fun onItemDeletion(key: String, storeName: String)
    // Returns List of (Key, StoreName)
    suspend fun findKeysToEvict(storeName: String, currentTimeMillis: Long = Clock.System.now().toEpochMilliseconds()): List<Pair<String, String>> // Returns List of (Key, StoreName)
}