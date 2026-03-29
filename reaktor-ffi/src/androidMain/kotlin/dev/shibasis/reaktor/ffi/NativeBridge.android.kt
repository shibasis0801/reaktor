package dev.shibasis.reaktor.ffi

actual fun nativeHermesHello(): String = try {
    Tester.hermesHello()
} catch (e: Throwable) {
    "Hermes native error: ${e.message}"
}
