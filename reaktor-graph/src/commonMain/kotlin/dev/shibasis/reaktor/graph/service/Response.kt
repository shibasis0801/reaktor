package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.js.JsExport


@Serializable
@JsExport
open class Response(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val statusCode: StatusCode = StatusCode.OK
) {
    @Transient
    private var transportHeadersOverride: MutableMap<String, String>? = null

    @Transient
    private var transportStatusCodeOverride: StatusCode? = null

    @JsExport.Ignore
    constructor(): this(mutableMapOf(), StatusCode.OK)

    val transportHeaders: MutableMap<String, String>
        get() = transportHeadersOverride ?: headers

    val transportStatusCode: StatusCode
        get() = transportStatusCodeOverride ?: statusCode

    val isSuccess: Boolean
        get() = transportStatusCode.code in 200..299

    internal fun applyTransportMetadata(
        headers: Map<String, String>,
        statusCode: StatusCode,
    ) {
        transportHeadersOverride = headers.toMutableMap()
        transportStatusCodeOverride = statusCode
    }
}
