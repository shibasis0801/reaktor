package dev.shibasis.reaktor.ffi.interop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class ImportedModules internal constructor(
    private val invoke: (String, String, String) -> String,
    private val json: Json,
) {
    fun module(name: String) = ImportedModule(name, invoke, json)
}

class ImportedModule internal constructor(
    private val name: String,
    private val invoke: (String, String, String) -> String,
    private val json: Json,
) {
    inline fun <reified Request, reified Response> function(operation: String) =
        function(operation, serializer<Request>(), serializer<Response>())

    fun <Request, Response> function(operation: String, request: KSerializer<Request>, response: KSerializer<Response>): (Request) -> Response =
        { input -> json.decodeFromString(response, invoke(name, operation, json.encodeToString(request, input))) }
}
