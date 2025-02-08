package dev.shibasis.reaktor.db.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

abstract class KeyValueStore<Controller>(
    controller: Controller,
    val name: String
): Adapter<Controller>(controller) {
    abstract suspend fun get(key: String): String?
    abstract suspend fun set(key: String, value: String)
    abstract suspend fun remove(key: String)
    abstract suspend fun clear()

    interface BlockingStore {

    }
    interface EncryptedStore {

    }
    interface TypedStore {

    }

    fun property(key: String) = KeyValueProperty(this, key)
}

class KeyValueProperty(
    store: KeyValueStore<*>,
    val key: String
): Adapter<KeyValueStore<*>>(store) {
    private var cachedValue: String? = null
    private var isCached: Boolean = false

    suspend fun get(): String? = suspended {
        if (!isCached) {
            cachedValue = get(key)
            isCached = true
        }
        cachedValue
    }

    suspend fun set(value: String?) = suspended {
        if (value != null) set(key, value) else remove(key)
        cachedValue = value
        isCached = true
    }
}

var Feature.KeyValueStore by CreateSlot<KeyValueStore<*>>()