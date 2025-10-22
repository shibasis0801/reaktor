package dev.shibasis.reaktor.navigation.util

import kotlinx.coroutines.flow.MutableStateFlow

class ObservableStack<T>(initialTop: T? = null) {
    val top = MutableStateFlow(initialTop)
    private val stack = ArrayDeque<T>()

    init {
        initialTop?.apply(stack::add)
    }

    val size: Int get() = stack.size
    fun isEmpty() = stack.isEmpty()

    val entries = stack as List<T>

    fun push(value: T) {
        stack.add(value)
        top.value = value
    }

    fun replace(value: T) {
        pop()
        push(value)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false

        stack.removeLast()
        top.value = stack.lastOrNull()
        return true
    }

    fun clear() {
        if (stack.isEmpty()) return

        stack.clear()
        top.value = null
    }
}