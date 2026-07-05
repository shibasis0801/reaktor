package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

const val REALTIME_CONNECT_SCOPE = "realtime:connect"
const val DEFAULT_REALTIME_TOKEN_TTL_SECONDS = 60
const val MAX_REALTIME_TOKEN_TTL_SECONDS = 120

@JsExport
@Serializable
data class RealtimeTokenRequest(
    val transport: String,
    val service: String,
    val contextId: String,
    val audience: String,
    val ttlSeconds: Int? = null,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD,
) : Request()

@JsExport
@Serializable
data class RealtimeTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int = DEFAULT_REALTIME_TOKEN_TTL_SECONDS,
    val audience: String,
    val contextId: String,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response()

@JsExport
abstract class RealtimeTokenService(baseUrl: String = "") : Service(baseUrl) {
    abstract val realtimeToken: PostHandler<RealtimeTokenRequest, RealtimeTokenResponse>
}

@JsExport
open class RealtimeTokenServiceClient(baseUrl: String) : RealtimeTokenService(baseUrl) {
    override val realtimeToken =
        PostHandler<RealtimeTokenRequest, RealtimeTokenResponse>("/realtime/token")
}
