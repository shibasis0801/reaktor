package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.DatabaseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlin.concurrent.Volatile
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class ReactiveDelegate<T : Any>(
    private val store: ObjectStore,
    private val key: String?,
    private val defaultValue: T,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope
) : ReadWriteProperty<Any?, T> {

    @Volatile
    private var cachedValue: T = defaultValue
    private var syncJob: Job? = null
    private var resolvedKey: String = key ?: ""

    private fun ensureInit(propertyKey: String) {
        if (syncJob != null) return

        resolvedKey = key ?: propertyKey

        scope.launch {
            val dbResult = store.get<T>(resolvedKey, serializer)
            if (dbResult != null) {
                cachedValue = dbResult.value
            }
        }

        syncJob = store.objectDatabase.events
            .filter { event ->
                val isMyStore = when(event) {
                    is DatabaseEvent.Write -> event.storeName == store.storeName && event.key == resolvedKey
                    is DatabaseEvent.Delete -> event.storeName == store.storeName && event.key == resolvedKey
                    is DatabaseEvent.Clear -> event.storeName == store.storeName
                    is DatabaseEvent.ClearAll -> true
                }
                isMyStore
            }
            .onEach { event ->
                if (event is DatabaseEvent.Write) {
                    val fresh = store.get(resolvedKey, serializer)
                    if (fresh != null) cachedValue = fresh.value
                } else {
                    cachedValue = defaultValue
                }
            }
            .launchIn(scope)
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        ensureInit(property.name)
        return cachedValue
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        ensureInit(property.name)
        cachedValue = value

        scope.launch {
            store.put(resolvedKey, value, serializer)
        }
    }
}