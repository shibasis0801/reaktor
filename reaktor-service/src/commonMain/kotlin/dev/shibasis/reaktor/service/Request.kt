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
    @Transient open var environment: Environment = defaultEnvironment
) {
    @Transient
    @JsExport.Ignore
    val attributes: MutableMap<String, Any?> = mutableMapOf()

    @JsExport.Ignore
    constructor(): this(mutableMapOf(), mutableMapOf(), mutableMapOf(), defaultEnvironment)

    companion object {
        /**
         * Tier stamped onto requests that do not set [environment] themselves; Service.kt sends it
         * as `X-Environment`. Clients set this once at startup. Stays PROD on the server and in
         * workers, where the tier arrives per-request on the header instead.
         */
        @JsExport.Ignore
        var defaultEnvironment: Environment = Environment.PROD
    }
}

