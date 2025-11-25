package dev.shibasis.reaktor.db.store.concrete

import dev.shibasis.reaktor.core.utils.epochTime
import dev.shibasis.reaktor.db.store.CachePolicy
import dev.shibasis.reaktor.db.store.StoredObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// todo AI wrote this, review.
class LRUCachePolicy(
    private val capacity: Int,
    private val cacheTime: Duration = 5.minutes
) : CachePolicy {

    private val mutex = Mutex()

    private data class Entry(
        var lastAccess: Long,
        var updatedAt: Long
    )

    // Per-storeName: key -> Entry (we do NOT rely on LinkedHashMap being access-ordered)
    private val lruByStore: MutableMap<String, LinkedHashMap<String, Entry>> = mutableMapOf()

    private fun storeMap(storeName: String): LinkedHashMap<String, Entry> =
        lruByStore.getOrPut(storeName) {
            LinkedHashMap(capacity)
        }

    override suspend fun <T : Any> onItemAccess(storedObject: StoredObject<T>): StoredObject<T>? =
        mutex.withLock {
            val now = epochTime()
            val key = storedObject.key
            val storeName = storedObject.storeName

            // TTL-based invalidation (except special key)
            if (key != "logged_in_user" &&
                (now - storedObject.updatedAt) > cacheTime.inWholeMilliseconds
            ) {
                lruByStore[storeName]?.remove(key)
                return null
            }

            val map = storeMap(storeName)
            val entry = map[key]

            if (entry == null) {
                // New entry: track as recently used
                map[key] = Entry(
                    lastAccess = now,
                    updatedAt = storedObject.updatedAt
                )
            } else {
                entry.lastAccess = now
                entry.updatedAt = storedObject.updatedAt
            }

            storedObject
        }

    override suspend fun onItemInsertion(key: String, storeName: String) =
        mutex.withLock {
            val now = epochTime()
            val map = storeMap(storeName)
            // Treat insertion as fresh, recently accessed
            map[key] = Entry(lastAccess = now, updatedAt = now)
        }

    override suspend fun onItemUpdate(key: String, storeName: String) =
        mutex.withLock {
            val now = epochTime()
            val map = storeMap(storeName)
            val entry = map[key]

            if (entry == null) {
                // Not seen before in this process – register it
                map[key] = Entry(lastAccess = now, updatedAt = now)
            } else {
                entry.lastAccess = now
                entry.updatedAt = now
            }
        }

    override suspend fun onItemDeletion(key: String, storeName: String): Unit =
        mutex.withLock {
            lruByStore[storeName]?.remove(key)
        }

    override suspend fun findKeysToEvict(
        storeName: String,
        currentTimeMillis: Long
    ): List<Pair<String, String>> =
        mutex.withLock {
            val map = lruByStore[storeName] ?: return emptyList()
            if (map.isEmpty()) return emptyList()

            val toEvict = mutableListOf<Pair<String, String>>()
            val cacheTimeMs = cacheTime.inWholeMilliseconds

            // 1) Time-based eviction first (except "logged_in_user")
            val timeIterator = map.entries.iterator()
            while (timeIterator.hasNext()) {
                val (key, entry) = timeIterator.next()
                if (key == "logged_in_user") continue

                if ((currentTimeMillis - entry.updatedAt) > cacheTimeMs) {
                    toEvict += key to storeName
                    timeIterator.remove()
                }
            }

            // 2) Capacity-based eviction: if still over capacity, evict least-recently-accessed entries
            if (capacity > 0 && map.size > capacity) {
                val overflow = map.size - capacity

                val victims = map.entries
                    .asSequence()
                    .filter { it.key != "logged_in_user" }
                    .sortedBy { it.value.lastAccess } // LRU first
                    .take(overflow)
                    .toList()

                for (victim in victims) {
                    val key = victim.key
                    toEvict += key to storeName
                    map.remove(key)
                }
            }

            toEvict
        }
}
