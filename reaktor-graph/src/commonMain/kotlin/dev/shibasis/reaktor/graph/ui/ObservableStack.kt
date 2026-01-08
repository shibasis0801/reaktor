package dev.shibasis.reaktor.graph.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class ObservableStack<T>(initialTop: T? = null) {
    val top = MutableStateFlow(initialTop)
    private val stack = ArrayDeque<T>()

    private val _entries = MutableStateFlow<List<T>>(emptyList())
    val entries: StateFlow<List<T>> = _entries

    init {
        initialTop?.apply(stack::add)
    }

    fun push(value: T) {
        stack.add(value)
        top.value = value
        _entries.value = stack.toList() // todo improve, shoddy.
    }

    fun replace(value: T) {
        pop()
        push(value)
    }

    fun pop(): T? {
        if (stack.isEmpty()) return null

        val removed = stack.removeLast()
        top.value = stack.lastOrNull()
        _entries.value = stack.toList() // todo improve, shoddy.
        return removed
    }

    fun clear() {
        if (stack.isEmpty()) return

        stack.clear()
        top.value = null

        _entries.value = stack.toList() // todo improve, shoddy.
    }
}