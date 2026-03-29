package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers

data class NativeFlexValue(
    val message: String,
    val answer: Int,
)

expect fun nativeFlexBufferBytes(): ByteArray

fun nativeFlexValue(): NativeFlexValue {
    val root = FlexBuffers.getRoot(nativeFlexBufferBytes())
    return NativeFlexValue(
        message = root["message"].toString().removeSurrounding("\""),
        answer = root["answer"].toInt(),
    )
}

fun nativeFlexPreview(): String = try {
    val value = nativeFlexValue()
    "${value.message} (${value.answer})"
} catch (error: Throwable) {
    "Flex native error: ${error.message ?: "unknown"}"
}
