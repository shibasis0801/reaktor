package dev.shibasis.reaktor.flatinvoker.flexbuffer

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer

class ListDecoder(val list: MutableList<Any>, var elementsCount: Int = 0): AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    private var elementIndex = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
        return elementIndex++
    }

    override fun decodeValue() = list.removeAt(0)

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = ListDecoder(list, descriptor.elementsCount)
    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return decodeInt().also { elementsCount = it }
    }
    override fun decodeSequentially(): Boolean = true
    override fun decodeNotNullMark(): Boolean {
        return decodeString() != "NULL"
    }
}

fun <T> decodeFromList(list: MutableList<Any>, deserializer: DeserializationStrategy<T>): T {
    val decoder = ListDecoder(list)
    return decoder.decodeSerializableValue(deserializer)
}

inline fun <reified T> decodeFromList(list: MutableList<Any>): T = decodeFromList(list, serializer())