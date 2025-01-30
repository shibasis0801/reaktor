package dev.shibasis.reaktor.navigation.structs

class ObservableStack<T>(initialTop: T? = null) {
    val top = Observable(initialTop)
    private val stack = ArrayDeque<T>()

    init {
        if (initialTop != null)
            stack.add(initialTop)
    }

    val size: Int
        get() = stack.size

    val entries = stack as List<T>

    fun push(value: T) {
        stack.add(value)
        top.value = value
    }

    fun replace(value: T) {
        stack.removeLast()
        stack.add(value)
        top.value = value
    }

    fun pop(): Boolean {
        if (stack.isNotEmpty()) {
            stack.removeLast()
            top.value = stack.lastOrNull()
            return true
        }
        else return false
    }
}