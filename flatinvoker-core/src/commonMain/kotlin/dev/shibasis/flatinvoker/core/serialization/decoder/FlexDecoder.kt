package dev.shibasis.flatinvoker.core.serialization.decoder

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule

class FlexDecoder(pointer: Long): AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val name = descriptor.getElementName(index)
        println("Decoding $name")
        return index++
    }

    override fun decodeString(): String {
        println("Decoding String")
        return "decoding string"
    }

    private val rawBuffer = FlexBuffer.GetBuffer(pointer)
    val flexBuffer = getRoot(ArrayReadBuffer(rawBuffer))

}