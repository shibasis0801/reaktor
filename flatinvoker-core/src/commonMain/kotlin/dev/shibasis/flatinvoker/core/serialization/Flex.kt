package dev.shibasis.flatinvoker.core.serialization

import dev.shibasis.flatinvoker.core.serialization.decoder.FlexDecoder
import dev.shibasis.flatinvoker.core.serialization.encoder.FlexEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer

fun<T> encodeToFlexBuffer(serializer: SerializationStrategy<T>, value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.flexBuffer
}

inline fun <reified T> encodeToFlexBuffer(value: T) = encodeToFlexBuffer(serializer(), value)


fun<T> decodeFromFlexBuffer(buffer: Long, deserializer: DeserializationStrategy<T>): T {
    val decoder = FlexDecoder(buffer)
    return decoder.decodeSerializableValue(deserializer)
}
inline fun <reified T> decodeFromFlexBuffer(buffer: Long): T = decodeFromFlexBuffer(buffer, serializer())