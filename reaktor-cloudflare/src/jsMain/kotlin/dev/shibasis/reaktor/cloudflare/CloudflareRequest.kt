package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.service.Environment
import dev.shibasis.reaktor.graph.service.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

interface CloudflareAwareRequest {
    var cloudflare: CloudflareContext?
}

@Serializable
open class CloudflareRequest(
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.STAGE,
) : Request(headers, queryParams, pathParams, environment), CloudflareAwareRequest {
    @Transient
    override var cloudflare: CloudflareContext? = null
}

fun Request.cloudflareOrNull(): CloudflareContext? = (this as? CloudflareAwareRequest)?.cloudflare

fun Request.requireCloudflare(): CloudflareContext =
    cloudflareOrNull() ?: error("Cloudflare context is only available for CloudflareAwareRequest handlers mounted through Service.toHono()")
