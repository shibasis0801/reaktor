package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ObjectFlow<T: Any>(
    val store: ObjectStore,
    val key: String,
    factory: suspend () -> StoredObject<T>? = { null }
) {
    private val _flow = MutableStateFlow<StoredObject<T>?>(null)
    val flow = _flow.asStateFlow()

    init {
        Dispatch.Default.launch {
            factory()?.also(::_internal_emit)
        }
    }

    fun get(): T? {
        return flow.value?.value
    }

    suspend inline fun <reified T: Any> set(value: T) {
        store[key] = value
    }

    fun _internal_emit(value: StoredObject<T>) { _flow.value = value }
}