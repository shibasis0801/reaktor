package dev.shibasis.flatinvoker.core.serialization.decoder

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.modules.EmptySerializersModule

class FlexDecoder(pointer: Long): AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()

    private val rawBuffer = FlexBuffer.GetBuffer(pointer)
    val flexBuffer = getRoot(ArrayReadBuffer(rawBuffer))

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return 0
    }
}