package dev.shibasis.reaktor.db.adapters

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.DependencyModule
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.core.framework.Feature
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

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

    suspend fun get(): String? = invokeSuspend {
        if (!isCached) {
            cachedValue = get(key)
            isCached = true
        }
        cachedValue
    }

    suspend fun set(value: String?) = invokeSuspend {
        if (value != null) set(key, value) else remove(key)
        cachedValue = value
        isCached = true
    }
}

var Feature.KeyValueStore by CreateSlot<KeyValueStore<*>>()