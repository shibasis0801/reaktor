package dev.shibasis.flatinvoker.core.ListSerializer

import com.google.flatbuffers.kotlin.value
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer


/*
Use this encoder to establish baseline for the fastest possible binary encoding.
The binary encoding will almost definitely be unusable.
It will help us optimise the FlexBuffer encoding and aim for a realistic target.
This comes very close to protobuf, but protobuf is faster.
FlatBuffer's promise of being faster than protobuf is for decoding.
But even encoding needs to be close to protobuf and faster than JSON.
Otherwise what is the point ?
*/
class ListEncoder: AbstractEncoder() {
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    val list = arrayListOf<Byte>()
    override fun encodeValue(value: Any) {
        when (value) {
            is Int -> list.add(value.toByte())
            is String -> list.addAll(value.map { it.code.toByte() })
            is Char -> list.add(value.code.toByte())
            is Double -> list.add(value.toLong().toByte())
            is Float -> list.add(value.toLong().toByte())
            is Short -> list.add(value.toByte())
            is Long -> list.add(value.toByte())
            is Boolean -> list.add(if (value) 1 else 0)
            is Byte -> list.add(value)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }

    override fun encodeNull() = encodeValue("NULL")
    override fun encodeNotNullMark() = encodeValue("!!")
    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        encodeInt(collectionSize)
        return this
    }
}

fun<T> encodeToList(serializer: SerializationStrategy<T>, value: T): ArrayList<Byte> {
    val encoder = ListEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.list
}

inline fun <reified T> encodeToList(value: T) = encodeToList(serializer(), value)

