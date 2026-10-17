package dev.shibasis.reaktor.ffi.interop

internal fun mobileHermesHello(): String = InteropRuntime.mobile().use {
    it.typescript.module("reaktor.diagnostics").function<Unit, String>("hello")(Unit)
}
