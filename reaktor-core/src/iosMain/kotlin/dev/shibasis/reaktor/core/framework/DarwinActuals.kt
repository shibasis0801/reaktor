package dev.shibasis.reaktor.core.framework

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

actual val __PLATFORM = PlatformType.DARWIN

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

actual fun ByteArray.toImageBitmap(): ImageBitmap {
    return Image.makeFromEncoded(this).toComposeImageBitmap()
}

