package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.AppleUser
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.serialization.Serializable

@Serializable
data class AppleBrowserStart(
    val codeChallenge: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Request.defaultEnvironment,
) : Request()

@Serializable
data class AppleBrowserAttempt(
    val state: String = "",
    val authorizationUrl: String = "",
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response()

@Serializable
data class AppleBrowserRedeem(
    val state: String,
    val codeVerifier: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Request.defaultEnvironment,
) : Request() {
    override fun toString(): String = "AppleBrowserRedeem(state=$state, codeVerifier=<redacted>)"
}

@Serializable
data class AppleBrowserCredential(
    val user: AppleUser? = null,
    val error: String? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response()

open class AppleBrowserApi : Service("reaktor.auth.apple-browser") {
    override val access = ServiceAccess(Callers.Everyone)
    open val start by post<AppleBrowserStart, AppleBrowserAttempt>("/auth/login/apple/start")
    open val redeem by post<AppleBrowserRedeem, AppleBrowserCredential>("/auth/login/apple/redeem")
}
