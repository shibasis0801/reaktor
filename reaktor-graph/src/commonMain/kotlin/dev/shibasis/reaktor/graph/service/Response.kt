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
    @JsExport.Ignore
    constructor(): this(mutableMapOf(), StatusCode.OK)
}