package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.ObjectAddress
import dev.shibasis.reaktor.db.StoredObject
import kotlin.reflect.KClass

interface ObjectCache {
    fun <T : Any> get(
        address: ObjectAddress,
        type: KClass<T>,
    ): StoredObject<T>?

    fun put(stored: StoredObject<*>)

    fun remove(address: ObjectAddress)

    fun clear(storeName: String)

    fun clear()
}

class LruObjectCache(
    private val maxEntries: Int = 256,
    private val maxBytes: Long = Long.MAX_VALUE,
) : ObjectCache {
    private val entries = linkedMapOf<ObjectAddress, StoredObject<*>>()
    private var totalBytes = 0L

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        address: ObjectAddress,
        type: KClass<T>,
    ): StoredObject<T>? {
        val stored = entries.remove(address) ?: return null

        if (!type.isInstance(stored.value)) {
            totalBytes -= stored.sizeBytes
            return null
        }

        entries[address] = stored
        return stored as StoredObject<T>
    }

    override fun put(stored: StoredObject<*>) {
        val address = ObjectAddress(stored.storeName, stored.key)

        entries.remove(address)?.let {
            totalBytes -= it.sizeBytes
        }

        entries[address] = stored
        totalBytes += stored.sizeBytes

        evictMemoryOnly()
    }

    private fun evictMemoryOnly() {
        val iterator = entries.iterator()

        while (
            iterator.hasNext() &&
            (entries.size > maxEntries || totalBytes > maxBytes)
        ) {
            val victim = iterator.next()
            totalBytes -= victim.value.sizeBytes
            iterator.remove()
        }
    }

    override fun remove(address: ObjectAddress) {
        entries.remove(address)?.let {
            totalBytes -= it.sizeBytes
        }
    }

    override fun clear(storeName: String) {
        val remove = entries.keys
            .filter { it.storeName == storeName }

        remove.forEach(::remove)
    }

    override fun clear() {
        entries.clear()
        totalBytes = 0L
    }
}
