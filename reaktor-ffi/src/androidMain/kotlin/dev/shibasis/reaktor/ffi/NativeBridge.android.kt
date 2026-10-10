package dev.shibasis.reaktor.ffi

actual fun nativeHermesHello(): String = dev.shibasis.reaktor.ffi.interop.mobileHermesHello()
