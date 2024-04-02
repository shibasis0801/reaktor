package dev.shibasis.flatinvoker.core.serialization

import dev.shibasis.flatinvoker.core.serialization.decoder.CallTraceDecoder
import dev.shibasis.flatinvoker.core.serialization.decoder.FlexDecoder
import dev.shibasis.flatinvoker.core.serialization.encoder.FlexEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer

inline fun <reified T> encodeToFlexBuffer(value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer(), value)
    return encoder.flexBuffer
}

inline fun <reified T> decodeFromFlexBuffer(buffer: Long): T {
    val decoder = FlexDecoder(buffer)
    return decoder.decodeSerializableValue(serializer())
}

inline fun <reified T> decodeToCallTrace(): T {
    val serializer = serializer<T>()
    val decoder = CallTraceDecoder(serializer.descriptor)
    // decoder.start()
    return decoder.decodeSerializableValue(serializer())
    // decoder.end()
}