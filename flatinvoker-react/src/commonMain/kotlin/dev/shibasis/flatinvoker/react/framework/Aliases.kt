package dev.shibasis.flatinvoker.react.framework

// kotlin/native weakref has this upper bound
// there is no weakref in stdlib because there can be 2 memory managers
// we always use concurrent gc so we can use this
expect class WeakRef<T: Any>(referred: T) {
    fun get(): T?
}