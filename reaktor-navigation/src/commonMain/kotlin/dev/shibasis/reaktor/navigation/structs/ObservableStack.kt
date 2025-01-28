package dev.shibasis.reaktor.navigation.structs

class ObservableStack<T>(
    initialTop: T
) {
    val top = Observable(initialTop)
    private val stack = ArrayDeque(listOf(initialTop))

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
        if (stack.size > 1) {
            stack.removeLast()
            top.value = stack.last()
            return true
        }
        else return false
    }
}