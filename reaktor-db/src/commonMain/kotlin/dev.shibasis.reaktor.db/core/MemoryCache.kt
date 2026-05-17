package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.StoredObject

class MemoryCache(private val capacity: Int = 100) {

    private val map = linkedMapOf<String, StoredObject<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): StoredObject<T>? {
        val stored = map.remove(key) ?: return null
        map[key] = stored
        return stored as StoredObject<T>
    }

    fun <T : Any> put(stored: StoredObject<T>) {
        map.remove(stored.key)
        if (map.size >= capacity) map.remove(map.keys.first())
        map[stored.key] = stored
    }

    fun remove(key: String) { map.remove(key) }
    fun clear() { map.clear() }
}
