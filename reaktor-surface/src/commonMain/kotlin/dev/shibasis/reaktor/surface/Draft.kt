package dev.shibasis.reaktor.surface

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

data class Edit<T>(val revision: Long, val value: T)

class Draft<T>(initial: T) {
    private val state = MutableStateFlow(Edit(0L, initial))
    val edits: StateFlow<Edit<T>> = state.asStateFlow()

    fun publish(value: T): Edit<T> = state.updateAndGet { Edit(it.revision + 1, value) }

    fun replace(base: Long, value: T): Boolean {
        var replaced = false
        state.update { current ->
            if (current.revision != base) current
            else Edit(base + 1, value).also { replaced = true }
        }
        return replaced
    }
}
