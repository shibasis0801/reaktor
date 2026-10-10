package dev.shibasis.reaktor.ffi.interop.kernel

/** A backend owns its runtime, callbacks and thread policy. No platform handle escapes this boundary. */
interface InteropKernel : AutoCloseable {
    fun callNative(module: String, operation: String, request: String): String
    fun callTypeScript(module: String, operation: String, request: String): String
    fun evaluate(source: String, sourceName: String)
}

fun interface HostDispatch {
    fun invoke(module: String, operation: String, request: String): String
}

internal expect fun mobileKernel(dispatch: HostDispatch): InteropKernel
