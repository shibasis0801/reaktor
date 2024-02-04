package dev.shibasis.reaktor.core.framework

import androidx.compose.ui.graphics.ImageBitmap

expect class WeakRef<T>(referred: T) {
    fun get(): T?
}

// Incomplete class, use atomicfu instead of completing this
expect class AtomicInt(value: Int) {
    fun getAndIncrement(): Int
}

expect fun ByteArray.toImageBitmap(): ImageBitmap



