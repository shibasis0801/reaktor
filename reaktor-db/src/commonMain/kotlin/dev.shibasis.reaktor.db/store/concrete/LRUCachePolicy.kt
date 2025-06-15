package dev.shibasis.reaktor.db.store.concrete

import dev.shibasis.reaktor.db.store.CachePolicy
import dev.shibasis.reaktor.db.store.StoredObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class LRUCachePolicy(
    private val capacity: Int,
    private val cacheTime: Duration = 5.minutes
) : CachePolicy {
    override suspend fun <T : Any> onItemAccess(storedObject: StoredObject<T>): StoredObject<T>? {
        val updatedAt: Long = storedObject.updatedAt
        val currentTime = Clock.System.now().toEpochMilliseconds()
        if (storedObject.key != "logged_in_user" && (currentTime - updatedAt) > cacheTime.inWholeMilliseconds) {
            return null
        }
        return storedObject
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