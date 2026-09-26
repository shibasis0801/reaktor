package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.ObjectAddress
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<ObjectAddress, StoredObject<*>>()
    private var totalBytes = 0L

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        address: ObjectAddress,
        type: KClass<T>,
    ): StoredObject<T>? = synchronized(lock) {
        val stored = entries.remove(address) ?: return@synchronized null

        if (!type.isInstance(stored.value)) {
            totalBytes -= stored.sizeBytes
            return@synchronized null
        }

        entries[address] = stored
        stored as StoredObject<T>
    }

    override fun put(stored: StoredObject<*>) = synchronized(lock) {
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

    override fun remove(address: ObjectAddress) = synchronized(lock) {
        entries.remove(address)?.let {
            totalBytes -= it.sizeBytes
        }
        Unit
    }

    override fun clear(storeName: String) = synchronized(lock) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.storeName == storeName) {
                totalBytes -= entry.value.sizeBytes
                iterator.remove()
            }
        }
    }

    override fun clear() = synchronized(lock) {
        entries.clear()
        totalBytes = 0L
    }
}
