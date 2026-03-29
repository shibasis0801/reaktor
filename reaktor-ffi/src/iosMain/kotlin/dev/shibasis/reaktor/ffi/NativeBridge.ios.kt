package dev.shibasis.reaktor.ffi

import dev.shibasis.reaktor.native.Reaktor_HermesHello
import kotlinx.cinterop.toKString

actual fun nativeHermesHello(): String = try {
    Reaktor_HermesHello()?.toKString() ?: "Hermes native: null"
} catch (e: Throwable) {
    "Hermes native error: ${e.message}"
}
