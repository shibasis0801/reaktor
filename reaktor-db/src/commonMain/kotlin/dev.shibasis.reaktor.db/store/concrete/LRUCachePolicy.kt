package dev.shibasis.reaktor.db.store.concrete

import dev.shibasis.reaktor.db.store.CachePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LRUCachePolicy(private val capacity: Int) : CachePolicy {
    override suspend fun onItemAccess(key: String, storeName: String) {

    }

    override suspend fun onItemInsertion(key: String, storeName: String) {

    }

    override suspend fun onItemUpdate(key: String, storeName: String) {

    }

    override suspend fun onItemDeletion(key: String, storeName: String) {

    }

    override suspend fun findKeysToEvict(storeName: String, currentTimeMillis: Long): List<Pair<String, String>> {
        return emptyList()
    }
}