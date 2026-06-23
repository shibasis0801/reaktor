package dev.shibasis.reaktor.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.js.JsExport

@JsExport
@Serializable
open class Request(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val queryParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val pathParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open var environment: Environment = Environment.STAGE
) {
    @Transient
    @JsExport.Ignore
    val attributes: MutableMap<String, Any?> = mutableMapOf()

    @JsExport.Ignore
    constructor(): this(mutableMapOf(), mutableMapOf(), mutableMapOf(), Environment.STAGE)
}

