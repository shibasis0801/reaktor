package dev.shibasis.reaktor.core.structs

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class ConcurrentMap<K, V> {
    private val ref: AtomicRef<Map<K, V>> = atomic(linkedMapOf())
    private val lock = SynchronizedObject()

    fun put(key: K, value: V) {
        synchronized(lock) {
            ref.value += (key to value)
        }
    }

    fun get(key: K): V? {
        return ref.value[key]
    }

    fun remove(key: K): V? {
        synchronized(lock) {
            val value = ref.value[key]
            ref.value -= key
            return value
        }
    }

    fun containsKey(key: K): Boolean {
        return ref.value.containsKey(key)
    }

    fun isEmpty(): Boolean {
        return ref.value.isEmpty()
    }

    fun forEach(action: (Map.Entry<K, V>) -> Unit) {
        ref.value.forEach(action)
    }
    fun clear() {
        synchronized(lock) {
            ref.value = emptyMap()
        }
    }

    val size: Int
        get() = ref.value.size
}