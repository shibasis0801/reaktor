package dev.shibasis.reaktor.core.serialization

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.core.FlexBuffer
import dev.shibasis.reaktor.core.serialization.decoder.FlexDecoder
import dev.shibasis.reaktor.core.serialization.encoder.FlexEncoder
import kotlinx.serialization.serializer

inline fun <reified T> encodeToFlexBuffer(value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer(), value)
    return encoder.flexBuffer
}

/**
 * Decodes a FlexBuffer pointer (from encodeToFlexBuffer) back into a Kotlin object.
 * The pointer must have been Finish()-ed before calling this.
 */
inline fun <reified T> decodeFromFlexBuffer(pointer: Long): T {
    val rawBuffer = FlexBuffer.GetBuffer(pointer)
    val root = getRoot(ArrayReadBuffer(rawBuffer))
    return decodeFromReference(root)
}

/**
 * Decodes a FlexBuffer Reference (already parsed) into a Kotlin object.
 */
inline fun <reified T> decodeFromReference(ref: Reference): T {
    val serializer = serializer<T>()
    val decoder = FlexDecoder(ref)
    return decoder.decodeSerializableValue(serializer)
}

/**
 * Decodes from raw FlexBuffer bytes into a Kotlin object.
 */
inline fun <reified T> decodeFromFlexBytes(bytes: ByteArray): T {
    val root = getRoot(ArrayReadBuffer(bytes))
    return decodeFromReference(root)
}

