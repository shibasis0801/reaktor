package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.native.Reaktor_FlexHello
import dev.shibasis.reaktor.native.Reaktor_FreeByteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents

@OptIn(ExperimentalForeignApi::class)
actual fun nativeFlexBufferBytes(): ByteArray {
    val buffer = Reaktor_FlexHello()
    return try {
        buffer.useContents {
            val pointer = data ?: return ByteArray(0)
            pointer.readBytes(size.toInt())
        }
    } finally {
        Reaktor_FreeByteBuffer(buffer)
    }
}
