package dev.shibasis.reaktor.io.adapters

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
}

var Feature.KeyValueStore by CreateSlot<KeyValueStore<*>>()
