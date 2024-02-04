package dev.shibasis.reaktor.core.framework

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

actual val __PLATFORM = PlatformType.JVM

actual class WeakRef<T> actual constructor(referred: T) {
    private val ref = WeakReference(referred)
    actual fun get() = ref.get()
}

actual class AtomicInt actual constructor(value: Int){
    private val data = AtomicInteger(value)
    actual fun getAndIncrement() = data.getAndIncrement()
}

// Needs to be removed
actual fun ByteArray.toImageBitmap(): ImageBitmap {
    return Image.makeFromEncoded(this).toComposeImageBitmap()
}

