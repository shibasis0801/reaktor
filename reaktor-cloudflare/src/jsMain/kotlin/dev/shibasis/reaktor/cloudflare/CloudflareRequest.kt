package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.service.Environment
import dev.shibasis.reaktor.graph.service.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

interface CloudflareAwareRequest {
    var cloudflareContext: CloudflareContext?
}

@Serializable
open class CloudflareRequest(
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.STAGE,
) : Request(headers, queryParams, pathParams, environment), CloudflareAwareRequest {
    @Transient
    override var cloudflareContext: CloudflareContext? = null
}

val Request.contextOrNull: CloudflareContext?
    get() = (this as? CloudflareAwareRequest)?.cloudflareContext

val Request.context: CloudflareContext
    get() = contextOrNull ?: error("Cloudflare context is only available for CloudflareAwareRequest handlers mounted through Service.toHono()")

@Deprecated("Use request.contextOrNull", ReplaceWith("contextOrNull"))
fun Request.cloudflareOrNull(): CloudflareContext? = contextOrNull

@Deprecated("Use request.context", ReplaceWith("context"))
fun Request.requireCloudflare(): CloudflareContext = context
