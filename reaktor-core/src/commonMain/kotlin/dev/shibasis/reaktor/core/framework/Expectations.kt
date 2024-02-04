package dev.shibasis.reaktor.core.framework

expect class WeakRef<T>(referred: T) {
    fun get(): T?
}

// Incomplete class, use atomicfu instead of completing this
expect class AtomicInt(value: Int) {
    fun getAndIncrement(): Int
}



