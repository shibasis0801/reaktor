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
         * Tier stamped onto requests that do not set [environment] themselves.
         *
         * Service.kt writes this into the `X-Environment` header, and the server routes
         * stageDb/prodDb from it. A hardcoded PROD default meant every client call site that
         * forgot to pass an environment silently claimed to be production — harmless while the
         * tiers shared one database, wrong once they did not.
         *
         * Clients set this once at startup (BestBuds does it from AppEnvironment). It stays PROD
         * on the server and in workers, where the environment arrives per-request on the header
         * instead, so their behaviour is unchanged.
         */
        @JsExport.Ignore
        var defaultEnvironment: Environment = Environment.PROD
    }
}

