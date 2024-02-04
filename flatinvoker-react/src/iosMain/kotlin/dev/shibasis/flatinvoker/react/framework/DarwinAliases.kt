package dev.shibasis.flatinvoker.react.framework

import kotlin.native.ref.WeakReference


actual class WeakRef<T : Any> actual constructor(referred: T) {
    val ref = WeakReference(referred)
    actual fun get() = ref.get()
}
