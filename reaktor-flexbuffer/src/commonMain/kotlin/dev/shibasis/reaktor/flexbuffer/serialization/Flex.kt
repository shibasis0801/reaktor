package dev.shibasis.reaktor.core.serialization

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.core.FlexBuffer
import dev.shibasis.reaktor.core.serialization.decoder.CallTraceDecoder
import dev.shibasis.reaktor.core.serialization.decoder.FlexDecoder
import dev.shibasis.reaktor.core.serialization.encoder.FlexEncoder
import dev.shibasis.reaktor.core.serialization.util.TypeCategory
import dev.shibasis.reaktor.core.serialization.util.typeCategory
import kotlinx.serialization.serializer

inline fun <reified T> encodeToFlexBuffer(value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer(), value)
    return encoder.flexBuffer
}

inline fun <reified T> decodeFromFlexBuffer(pointer: Long): T {
    val rawBuffer = FlexBuffer.GetBuffer(pointer)
    val flexBuffer = getRoot(ArrayReadBuffer(rawBuffer))
    val category = flexBuffer.typeCategory()
    val serializer = serializer<T>()
    val decoder = FlexDecoder(flexBuffer, serializer.descriptor)
    return decoder.decodeSerializableValue(serializer)
}

inline fun <reified T> printDecoderCallTrace(): T {
    val serializer = serializer<T>()
    val decoder = CallTraceDecoder(serializer.descriptor)
    return decoder.decodeSerializableValue(serializer())
}

inline fun decodeCategory(ref: Reference) {
    val category = ref.typeCategory()
    when (category) {
        TypeCategory.PRIMITIVE -> {

        }
        TypeCategory.INVALID -> TODO()
        TypeCategory.KEY -> TODO()
        TypeCategory.STRING -> TODO()
        TypeCategory.INDIRECT_PRIMITIVE -> TODO()
        TypeCategory.MAP -> TODO()
        TypeCategory.VECTOR -> TODO()
        TypeCategory.TYPED_VECTOR -> TODO()
        TypeCategory.BLOB -> TODO()
    }
}
