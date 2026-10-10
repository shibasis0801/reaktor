@file:Suppress("KotlinJniMissingFunction")
package dev.shibasis.reaktor.ffi.interop.kernel

import dev.shibasis.reaktor.ffi.interop.generated.TypeScriptBundle

internal actual fun mobileKernel(dispatch: HostDispatch): InteropKernel =
    AndroidKernel(dispatch).withBundle(TypeScriptBundle.source)

@androidx.annotation.Keep
internal class AndroidKernel(private val dispatch: HostDispatch) : InteropKernel {
    private var handle = create()
    private fun liveHandle() = handle.also { check(it != 0L) { "Interop runtime is closed" } }
    override fun callNative(module: String, operation: String, request: String) = nativeCall(liveHandle(), module, operation, request)
    override fun callTypeScript(module: String, operation: String, request: String) = typescriptCall(liveHandle(), module, operation, request)
    override fun evaluate(source: String, sourceName: String) = evaluateSource(liveHandle(), source, sourceName)
    override fun close() {
        if (handle == 0L) return
        destroy(handle)
        handle = 0
    }

    // Called by the owned C++ callback; R8 must retain the method and class names.
    @androidx.annotation.Keep
    fun invokeHost(module: String, operation: String, request: String) = dispatch.invoke(module, operation, request)
    private external fun create(): Long
    private external fun destroy(handle: Long)
    private external fun nativeCall(handle: Long, module: String, operation: String, request: String): String
    private external fun typescriptCall(handle: Long, module: String, operation: String, request: String): String
    private external fun evaluateSource(handle: Long, source: String, name: String)

    companion object { init { System.loadLibrary("ReaktorFFI") } }
}
