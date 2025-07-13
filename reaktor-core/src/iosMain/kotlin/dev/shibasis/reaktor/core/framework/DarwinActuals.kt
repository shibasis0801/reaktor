package dev.shibasis.reaktor.core.framework

import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

actual class WeakRef<T> actual constructor(referred: T) {
    @OptIn(ExperimentalNativeApi::class)
    val ref = referred?.let { WeakReference(referred) }
    @OptIn(ExperimentalNativeApi::class)
    actual fun get() = ref?.get()
}


actual class AtomicInt actual constructor(value: Int){
    private val data = AtomicInt(value)
    actual fun getAndIncrement() = data.getAndIncrement()
}