package dev.shibasis.reaktor.experiments.cloudflarehello.files

import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.graph.service.Response
import kotlinx.serialization.Serializable

@Serializable
class FileProbeRequest : CloudflareRequest()

@Serializable
class FileProbeResponse(
    val storage: String,
    val durable: Boolean,
    val key: String,
    val previous: String? = null,
    val written: String? = null,
    val readBack: String? = null,
    val existsAfterWrite: Boolean = false,
    val matches: Boolean = false,
    val note: String,
    val error: String? = null,
) : Response()
