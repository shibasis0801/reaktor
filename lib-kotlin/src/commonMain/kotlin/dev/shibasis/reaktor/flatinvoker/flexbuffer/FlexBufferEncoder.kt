package dev.shibasis.reaktor.flatinvoker.flexbuffer

import com.google.flatbuffers.kotlin.value
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer


/*
This is absolutely phenomenal for Flexbuffers and everything actually
I need to figure out how to improve the kotlin-flatbuffers and then this would work
The encoder/decoders have beginCollection, beginStructure and endStructure
These structure functions carry information regarding lists / classes, etc
I can easily use this to create vectors and maps in FlexBuffers

But the flexbuffers kotlin port is not working properly
It blows up exponentially in size if the json is even a little large
 */
class ListEncoder: AbstractEncoder() {
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    val list = mutableListOf<Any>()
    override fun encodeValue(value: Any) {
        list.add(value)
    }

    override fun encodeNull() = encodeValue("NULL")
    override fun encodeNotNullMark() = encodeValue("!!")

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        encodeInt(collectionSize)
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return super.beginStructure(descriptor)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        super.endStructure(descriptor)
    }
}

fun <T>encodeToList(serializer: SerializationStrategy<T>, value: T): MutableList<Any> {
    val encoder = ListEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.list
}

inline fun <reified T> encodeToList(value: T) = encodeToList(serializer(), value)
