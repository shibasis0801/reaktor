package dev.shibasis.reaktor.core.framework

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

actual val __PLATFORM = PlatformType.ANDROID

actual class WeakRef<T> actual constructor(referred: T) {
    private val ref = WeakReference(referred)
    actual fun get() = ref.get()
}

actual class AtomicInt actual constructor(value: Int){
    private val data = AtomicInteger(value)
    actual fun getAndIncrement() = data.getAndIncrement()
}
