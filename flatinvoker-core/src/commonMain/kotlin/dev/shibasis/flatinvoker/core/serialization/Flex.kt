package dev.shibasis.flatinvoker.core.serialization

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.decoder.CallTraceDecoder
import dev.shibasis.flatinvoker.core.serialization.decoder.FlexDecoder
import dev.shibasis.flatinvoker.core.serialization.encoder.FlexEncoder
import kotlinx.serialization.serializer

inline fun <reified T> encodeToFlexBuffer(value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer(), value)
    return encoder.flexBuffer
}

inline fun <reified T> decodeFromFlexBuffer(pointer: Long): T {
    val rawBuffer = FlexBuffer.GetBuffer(pointer)
    val flexBuffer = getRoot(ArrayReadBuffer(rawBuffer))
    val serializer = serializer<T>()
    val decoder = FlexDecoder(flexBuffer, serializer.descriptor)
    return decoder.decodeSerializableValue(serializer)
}

inline fun <reified T> printDecoderCallTrace(): T {
    val serializer = serializer<T>()
    val decoder = CallTraceDecoder(serializer.descriptor)
    return decoder.decodeSerializableValue(serializer())
}