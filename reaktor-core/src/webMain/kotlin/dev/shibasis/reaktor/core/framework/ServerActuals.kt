package dev.shibasis.reaktor.core.framework

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual val __PLATFORM = PlatformType.WEB

// noop
actual class WeakRef<T> actual constructor(referred: T) {
    private val ref = referred
    actual fun get(): T? {
        return ref
    }
}

actual class AtomicInt actual constructor(value: Int){
    private var data = value
    actual fun getAndIncrement(): Int {
        val result = data
        data += 1
        return result
    }
}


// Needs to be removed
actual fun ByteArray.toImageBitmap(): ImageBitmap {
    return Image.makeFromEncoded(this).toComposeImageBitmap()
}

