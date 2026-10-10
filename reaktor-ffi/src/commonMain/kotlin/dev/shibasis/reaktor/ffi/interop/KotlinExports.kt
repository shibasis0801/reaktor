package dev.shibasis.reaktor.ffi.interop

import dev.shibasis.reaktor.ffi.interop.kernel.HostDispatch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class KotlinExports internal constructor(private val json: Json) : HostDispatch {
    private val modules = linkedMapOf<String, ExportedModule>()
    fun module(name: String, configure: ExportedModule.() -> Unit) {
        require(name.isNotBlank()) { "An exported module needs a name" }
        require(name !in modules) { "Module '$name' is already exported" }
        modules[name] = ExportedModule(json).apply(configure)
    }
    override fun invoke(module: String, operation: String, request: String): String =
        requireNotNull(modules[module]) { "No Kotlin module '$module' is exported" }.invoke(operation, request)
    internal fun clear() = modules.clear()
}

class ExportedModule internal constructor(private val json: Json) {
    private val functions = linkedMapOf<String, (String) -> String>()
    inline fun <reified Request, reified Response> function(operation: String, noinline implementation: (Request) -> Response) =
        function(operation, serializer<Request>(), serializer<Response>(), implementation)

    fun <Request, Response> function(operation: String, request: KSerializer<Request>, response: KSerializer<Response>, implementation: (Request) -> Response) {
        require(operation.isNotBlank() && operation !in functions) { "Operation '$operation' must have a unique nonempty name" }
        functions[operation] = { input -> json.encodeToString(response, implementation(json.decodeFromString(request, input))) }
    }
    internal fun invoke(operation: String, request: String): String =
        requireNotNull(functions[operation]) { "No operation '$operation' is exported" }(request)
}
