package dev.shibasis.reaktor.ffi.interop

import dev.shibasis.reaktor.ffi.interop.kernel.HostDispatch
import dev.shibasis.reaktor.ffi.interop.kernel.InteropKernel
import dev.shibasis.reaktor.ffi.interop.kernel.mobileKernel
import kotlinx.serialization.json.Json

/** One lifetime for native modules, TypeScript modules and Kotlin exports. */
class InteropRuntime private constructor(
    val kernel: InteropKernel,
    val kotlin: KotlinExports,
    val json: Json,
) : AutoCloseable {
    val native = ImportedModules(kernel::callNative, json)
    val typescript = ImportedModules(kernel::callTypeScript, json)

    fun loadTypeScript(source: String, sourceName: String = "module.js") = kernel.evaluate(source, sourceName)
    override fun close() { kernel.close(); kotlin.clear() }

    companion object {
        fun mobile(json: Json = Json): InteropRuntime = using(json, ::mobileKernel)

        /** Custom backends and future FFM/Wasm adapters use the same typed API. */
        fun using(json: Json = Json, factory: (HostDispatch) -> InteropKernel): InteropRuntime {
            val exports = KotlinExports(json)
            return InteropRuntime(factory(exports), exports, json)
        }
    }
}
