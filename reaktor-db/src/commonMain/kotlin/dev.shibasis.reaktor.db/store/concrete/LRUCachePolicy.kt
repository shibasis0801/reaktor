package dev.shibasis.reaktor.db.store.concrete

import dev.shibasis.reaktor.db.store.CachePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LRUCachePolicy(private val capacity: Int) : CachePolicy {
    private val storeUsage = mutableMapOf<String, LinkedHashMap<String, Unit>>()
    private val mutex = Mutex()

    private suspend fun getUsageForStore(storeName: String): LinkedHashMap<String, Unit> {
        return mutex.withLock {
            storeUsage.getOrPut(storeName) { LinkedHashMap() }
        }
    }

    override suspend fun onItemAccess(key: String, storeName: String) {
        mutex.withLock {
            val usage = getUsageForStore(storeName)
            usage.remove(key) // Remove and add to make it the most recently used
            usage[key] = Unit
        }
    }

    override suspend fun onItemInsertion(key: String, storeName: String) {
        mutex.withLock {
            val usage = getUsageForStore(storeName)
            usage[key] = Unit
        }
    }

    override suspend fun onItemUpdate(key: String, storeName: String) {
        mutex.withLock {
            val usage = getUsageForStore(storeName)
            usage.remove(key)
            usage[key] = Unit
        }
    }

    override suspend fun onItemDeletion(key: String, storeName: String) {
        mutex.withLock {
            val usage = getUsageForStore(storeName)
            usage.remove(key)
        }
    }

    override suspend fun findKeysToEvict(storeName: String, currentTimeMillis: Long): List<Pair<String, String>> {
        return mutex.withLock {
            val usage = getUsageForStore(storeName)
            val keysToRemove = mutableListOf<Pair<String, String>>()

            if (usage.size > capacity) {
                val keysToRemoveCount = usage.size - capacity
                val keysIterator = usage.keys.iterator()

                repeat(keysToRemoveCount) {
                    if (keysIterator.hasNext()) {
                        keysToRemove.add(Pair(keysIterator.next(), storeName))
                    }
                }

                keysToRemove.forEach { (key, _) -> usage.remove(key) }
            }

            keysToRemove
        }
    }
}