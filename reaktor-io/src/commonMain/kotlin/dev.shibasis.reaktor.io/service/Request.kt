package dev.shibasis.reaktor.io.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
@Serializable
open class Request(
    @Transient open val headers: MutableMap<String, String> = mutableMapOf(),
    @Transient open val queryParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val pathParams: MutableMap<String, String> = mutableMapOf(),
    @Transient open val environment: Environment = Environment.STAGE
) {
    @JsExport.Ignore
    constructor(): this(mutableMapOf(), mutableMapOf(), mutableMapOf(), Environment.STAGE)
}


