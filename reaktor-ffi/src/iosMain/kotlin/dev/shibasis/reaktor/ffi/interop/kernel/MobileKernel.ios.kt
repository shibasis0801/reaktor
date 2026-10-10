@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.ffi.interop.kernel

import dev.shibasis.reaktor.ffi.interop.generated.TypeScriptBundle
import dev.shibasis.reaktor.native.*
import cnames.structs.ReaktorInteropResult
import kotlinx.cinterop.*

internal actual fun mobileKernel(dispatch: HostDispatch): InteropKernel =
    AppleKernel(dispatch).withBundle(TypeScriptBundle.source)

private fun invokeHost(context: COpaquePointer?, module: CPointer<ByteVar>?, operation: CPointer<ByteVar>?, request: CPointer<ByteVar>?): CPointer<ReaktorInteropResult>? =
    try {
        val dispatch = requireNotNull(context).asStableRef<HostDispatch>().get()
        ReaktorInterop_success(dispatch.invoke(requireNotNull(module).toKString(), requireNotNull(operation).toKString(), requireNotNull(request).toKString()))
    } catch (failure: Throwable) { ReaktorInterop_failure(failure.message ?: "Kotlin host operation failed") }

private fun consume(result: CPointer<ReaktorInteropResult>?): String {
    val owned = requireNotNull(result) { "Native runtime returned no result" }
    try {
        ReaktorInterop_error(owned)?.toKString()?.let { throw IllegalStateException(it) }
        return requireNotNull(ReaktorInterop_value(owned)).toKString()
    } finally { ReaktorInterop_release(owned) }
}

internal class AppleKernel(dispatch: HostDispatch) : InteropKernel {
    private val host = StableRef.create(dispatch)
    private var handle = ReaktorInterop_create(staticCFunction(::invokeHost), host.asCPointer()).also {
        if (it == null) { host.dispose(); error("Unable to create native interop runtime") }
    }
    private fun liveHandle() = checkNotNull(handle) { "Interop runtime is closed" }
    override fun callNative(module: String, operation: String, request: String) = consume(ReaktorInterop_native(liveHandle(), module, operation, request))
    override fun callTypeScript(module: String, operation: String, request: String) = consume(ReaktorInterop_typescript(liveHandle(), module, operation, request))
    override fun evaluate(source: String, sourceName: String) { consume(ReaktorInterop_evaluate(liveHandle(), source, sourceName)) }
    override fun close() {
        val current = handle ?: return
        consume(ReaktorInterop_close(current))
        handle = null
        host.dispose()
    }
}
