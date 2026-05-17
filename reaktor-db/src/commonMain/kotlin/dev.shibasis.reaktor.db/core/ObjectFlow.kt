package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.StoredObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ObjectFlow<T: Any>(
    val store: ObjectStore,
    val key: String,
    private val factory: suspend () -> StoredObject<T>? = { null }
) {
    private val _flow = MutableStateFlow<StoredObject<T>?>(null)

    /** Observe this flow. Triggers a lazy load from the database on first subscription. */
    val flow: StateFlow<StoredObject<T>?> get() = _flow

    val value: T? get() = _flow.value?.value

    /** Load the initial value from the database. Call once after creation if eager loading is needed. */
    suspend fun load() {
        if (_flow.value == null) {
            factory()?.also(::update)
        }
    }

    fun update(stored: StoredObject<T>) {
        _flow.value = stored
    }
}
