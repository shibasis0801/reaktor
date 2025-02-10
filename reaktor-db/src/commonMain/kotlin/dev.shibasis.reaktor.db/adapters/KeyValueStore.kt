package dev.shibasis.reaktor.db.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow

abstract class KeyValueStore<Controller>(
    controller: Controller,
    val name: String
): Adapter<Controller>(controller) {
    protected abstract suspend fun get(key: String): String?
    protected abstract suspend fun set(key: String, value: String)
    protected abstract suspend fun remove(key: String)
    protected abstract suspend fun clear()

    fun property(key: String) = PropertyFlow(key, ::get, ::set, ::remove)
}

class PropertyFlow(
    private val key: String,
    private val get: suspend (String) -> String?,
    private val set: suspend (String, String) -> Unit,
    private val clear: suspend (String) -> Unit,
    private val flow: MutableStateFlow<String?> = MutableStateFlow(null),
): MutableStateFlow<String?> by flow {
    override suspend fun emit(value: String?) {
        if (value == null) clear(key) else set(key, value)
        flow.emit(value)
    }

    override suspend fun collect(collector: FlowCollector<String?>): Nothing {
        compareAndSet(null, get(key))
        flow.collect(collector)
    }

    suspend fun clear() {
        clear(key)
    }
}



var Feature.KeyValueStore by CreateSlot<KeyValueStore<*>>()