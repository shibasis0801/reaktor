package com.myntra.appscore.batcave.framework

import java.lang.ref.WeakReference

actual class WeakRef<T: Any> actual constructor(referred: T) {
    val ref = WeakReference(referred)
    actual fun get() = ref.get()
}