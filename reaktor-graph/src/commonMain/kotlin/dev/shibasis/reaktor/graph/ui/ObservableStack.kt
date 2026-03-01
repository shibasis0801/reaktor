package dev.shibasis.reaktor.graph.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class ObservableStack<T>(initialTop: T? = null) {
    val top = MutableStateFlow(initialTop)
    private val stack = ArrayDeque<T>()

    private val _entries = MutableStateFlow<List<T>>(emptyList())
    val entries: StateFlow<List<T>> = _entries

    private var version = 0

    init {
        initialTop?.let {
            stack.add(it)
            _entries.value = listOf(it)
        }
    }

    private fun emitSnapshot() {
        version++
        _entries.value = ArrayList(stack)
    }

    fun push(value: T) {
        stack.add(value)
        top.value = value
        emitSnapshot()
    }

    fun replace(value: T) {
        if (stack.isNotEmpty()) {
            stack.removeLast()
        }
        stack.add(value)
        top.value = value
        emitSnapshot()
    }

    fun pop(): T? {
        if (stack.isEmpty()) return null

        val removed = stack.removeLast()
        top.value = stack.lastOrNull()
        emitSnapshot()
        return removed
    }

    fun clear() {
        if (stack.isEmpty()) return

        stack.clear()
        top.value = null
        emitSnapshot()
    }
}
