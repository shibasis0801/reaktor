package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.StoredObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface EvictionPolicy {
    fun <T : Any> isValid(stored: StoredObject<T>): Boolean
    fun onWrite(key: String, sizeBytes: Long): List<String>
    fun onDelete(key: String)
    fun clear()
}

object NoEviction : EvictionPolicy {
    override fun <T : Any> isValid(stored: StoredObject<T>) = true
    override fun onWrite(key: String, sizeBytes: Long) = emptyList<String>()
    override fun onDelete(key: String) {}
    override fun clear() {}
}

class LruEviction(
    private val ttl: Duration = 5.minutes,
    private val maxEntries: Int = Int.MAX_VALUE,
    private val maxBytes: Long = Long.MAX_VALUE
) : EvictionPolicy {

    private data class Entry(val key: String, var sizeBytes: Long)

    private val entries = linkedMapOf<String, Entry>()
    private var totalBytes = 0L

    override fun <T : Any> isValid(stored: StoredObject<T>): Boolean {
        val age = Clock.System.now().toEpochMilliseconds() - stored.updatedAt
        return age <= ttl.inWholeMilliseconds
    }

    override fun onWrite(key: String, sizeBytes: Long): List<String> {
        val existing = entries.remove(key)
        if (existing != null) totalBytes -= existing.sizeBytes

        entries[key] = Entry(key, sizeBytes)
        totalBytes += sizeBytes

        val evictions = mutableListOf<String>()
        val iter = entries.iterator()
        while (iter.hasNext() && (entries.size > maxEntries || totalBytes > maxBytes)) {
            val victim = iter.next()
            iter.remove()
            totalBytes -= victim.value.sizeBytes
            evictions.add(victim.key)
        }
        return evictions
    }

    override fun onDelete(key: String) {
        entries.remove(key)?.let { totalBytes -= it.sizeBytes }
    }

    override fun clear() {
        entries.clear()
        totalBytes = 0
    }
}
