package dev.shibasis.reaktor.core.framework

import android.graphics.BitmapFactory
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

// replace this from pragati
actual val __PLATFORM = PlatformType.ANDROID

actual class WeakRef<T> actual constructor(referred: T) {
    private val ref = WeakReference(referred)
    actual fun get() = ref.get()
}

actual class AtomicInt actual constructor(value: Int){
    private val data = AtomicInteger(value)
    actual fun getAndIncrement() = data.getAndIncrement()
}

actual fun ByteArray.toImageBitmap(): ImageBitmap {
    return BitmapFactory.decodeByteArray(this, 0, size).asImageBitmap()
}

